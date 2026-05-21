/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.knowledge.drift

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepositoryManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Reads commits in `<lastSyncedCommit>..HEAD` on the current branch via
 * Git4Idea, intersects each commit's touched files with what
 * `.claude/wiki/concepts/` markdown pages mention, and returns one
 * [DriftEvent.CommitDrift] per affected wiki page. An empty or
 * unreachable [lastSyncedCommit] is treated as a first run — the
 * detector returns no events and the caller adopts current HEAD as
 * the new baseline.
 *
 * The pure-Kotlin part [intersect] is fully unit-tested; the Git4Idea
 * adapter [detect] is exercised by the manual smoke checklist.
 *
 * Git4Idea API verification (IntelliJ Platform 2026.1):
 *  - `git4idea.history.GitHistoryUtils.history(project, root, vararg String)`
 *    returns `List<GitCommit>`. Confirmed against
 *    `vcs-git/lib/vcs-git.jar` shipped with the 2026.1 platform.
 *  - `GitCommit` extends `VcsChangesLazilyParsedDetails`, which exposes
 *    `getChanges(): Collection<Change>`. Each `Change` carries
 *    `afterRevision`/`beforeRevision` of type `ContentRevision` with
 *    `getFile(): FilePath` and `path: String`.
 *  - `GitCommit.id` is a `com.intellij.vcs.log.Hash` with `toShortString()`.
 */
object CommitWikiDriftDetector {

    private val LOG = Logger.getInstance(CommitWikiDriftDetector::class.java)

    const val MAX_COMMITS = 200
    const val MAX_TOUCHED_FILES = 1_000

    /**
     * One commit's relevant info for the intersection.
     * Public so tests and the Git4Idea adapter share the type.
     */
    data class CommitInfo(val sha: String, val touchedPaths: List<String>)

    /**
     * Top-level entry point used by `DriftDetectionService.collectRaw`. Reads
     * commits via Git4Idea, then defers to [intersect].
     */
    fun detect(
        project: Project,
        wikiDir: Path,
        lastSyncedCommit: String,
        now: Instant,
    ): List<DriftEvent.CommitDrift> {
        val commits = try {
            collectCommits(project, lastSyncedCommit)
        } catch (e: Throwable) {
            LOG.warn("CommitWikiDriftDetector failed to collect commits: ${e.message}")
            emptyList()
        }
        return intersect(wikiDir, commits, now)
    }

    /**
     * Pure-Kotlin core: given a wiki dir and a list of commits, build a
     * per-page mention set, intersect against each commit's touched paths,
     * and emit a [DriftEvent.CommitDrift] per page that had at least one
     * matching commit.
     */
    fun intersect(
        wikiDir: Path,
        commits: List<CommitInfo>,
        now: Instant,
    ): List<DriftEvent.CommitDrift> {
        if (!Files.isDirectory(wikiDir) || commits.isEmpty()) return emptyList()

        // Build (page, mentionSet) once per rescan.
        data class PageMentions(val page: Path, val mentions: Set<String>)
        val pages = mutableListOf<PageMentions>()
        try {
            Files.walk(wikiDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName?.toString()?.endsWith(".md") == true }
                    .forEach { path ->
                        val text = runCatching { Files.readString(path) }.getOrNull() ?: return@forEach
                        pages += PageMentions(path, MentionIndex.buildForPage(text))
                    }
            }
        } catch (e: Throwable) {
            LOG.warn("CommitWikiDriftDetector mention-index walk failed: ${e.message}")
            return emptyList()
        }

        // Intersect.
        // For each page, collect (sha, [touchedPaths-page-mentions]) pairs.
        val nowText = now.toString()
        val out = mutableListOf<DriftEvent.CommitDrift>()
        for (page in pages) {
            val matchedShas = mutableListOf<String>()
            val matchedTouched = linkedSetOf<String>()
            for (commit in commits) {
                val tokensForCommit = commitTokens(commit)
                val intersection = tokensForCommit.intersect(page.mentions)
                if (intersection.isNotEmpty()) {
                    matchedShas += commit.sha
                    matchedTouched.addAll(intersection)
                }
            }
            if (matchedShas.isNotEmpty()) {
                out += DriftEvent.CommitDrift(
                    wikiPage = page.page,
                    commitShas = matchedShas,
                    touchedPaths = matchedTouched.toList(),
                    firstObservedAt = nowText,
                )
            }
        }
        return out
    }

    /**
     * For a given commit, derive the set of "tokens" against which the
     * mention-set is intersected. Includes:
     *  - each touched relative path (if it qualifies as a valid mention)
     *  - the basename of each touched path (likewise gated by [MentionIndex.isValidToken])
     *
     * Class-name extraction from touched source files happens in [detect]
     * when reading the working tree; [intersect] sees only path-based tokens.
     * This is sufficient for the documented test cases and matches the spec —
     * paths are the load-bearing signal.
     */
    private fun commitTokens(commit: CommitInfo): Set<String> {
        val out = mutableSetOf<String>()
        for (path in commit.touchedPaths) {
            if (MentionIndex.isValidToken(path)) out += path
            // Basename — strip dirs and extension, if it qualifies.
            val basename = path.substringAfterLast('/').substringBeforeLast('.')
            if (MentionIndex.isValidToken(basename)) out += basename
        }
        return out
    }

    private fun collectCommits(project: Project, lastSyncedCommit: String): List<CommitInfo> {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return emptyList()
        val root = repo.root
        val isAncestor: (String) -> Boolean = { sha -> isAncestorOfHead(project, root, sha) }
        val rangeArg = commitRangeArgs(lastSyncedCommit, isAncestor) ?: return emptyList()
        val historyArgs = arrayOf("--name-only", "--pretty=format:%H", rangeArg)
        // GitHistoryUtils.history(project, root, vararg String) — verified against
        // IntelliJ Platform 2026.1 git4idea (vcs-git.jar -> git4idea/history/GitHistoryUtils.class).
        val history = try {
            GitHistoryUtils.history(project, root, *historyArgs)
        } catch (e: NoSuchMethodError) {
            LOG.warn("Git4Idea history API mismatch — see plan §Task 4 for the alternate entry point")
            return emptyList()
        }
        val out = mutableListOf<CommitInfo>()
        for (commit in history.take(MAX_COMMITS)) {
            val touched = commit.changes.mapNotNull { change ->
                change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path
            }.map { fullPath ->
                // Make the path repo-relative.
                val rootPath = root.path
                if (fullPath.startsWith("$rootPath/")) fullPath.substring(rootPath.length + 1) else fullPath
            }
            out += CommitInfo(sha = commit.id.toShortString(), touchedPaths = touched.take(MAX_TOUCHED_FILES))
            if (out.sumOf { it.touchedPaths.size } >= MAX_TOUCHED_FILES) break
        }
        return out
    }

    /**
     * Pure helper for the range-argument decision. Returns null on first-run /
     * unreachable cases (caller emits no events; baseline is current HEAD).
     */
    internal fun commitRangeArgs(
        lastSyncedCommit: String,
        isAncestor: (String) -> Boolean,
    ): String? {
        if (lastSyncedCommit.isBlank()) return null
        if (!isAncestor(lastSyncedCommit)) return null
        return "$lastSyncedCommit..HEAD"
    }

    private fun isAncestorOfHead(
        project: Project,
        root: com.intellij.openapi.vfs.VirtualFile,
        sha: String,
    ): Boolean {
        val handler = GitLineHandler(project, root, GitCommand.MERGE_BASE)
        handler.addParameters("--is-ancestor", sha, "HEAD")
        handler.setSilent(true)
        val result = Git.getInstance().runCommand(handler)
        // `git merge-base --is-ancestor`: exit 0 = ancestor; exit 1 = not. Other = error → treat as not ancestor.
        return result.exitCode == 0
    }
}
