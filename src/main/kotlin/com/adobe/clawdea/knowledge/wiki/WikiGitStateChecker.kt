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
package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.commands.handlers.WikiRelocateHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.GitUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that the three wiki state files have the git tracking status the
 * team-mode contract expects, and offers one-click fixes when they don't.
 *
 * Only runs when [WikiLocator.isTeamMode] is true. The three checked files:
 *   - `.clawdea/config.json`            — must be tracked, not ignored.
 *   - `.clawdea/wiki-state.local.json`  — must be ignored, not tracked.
 *   - `<wikiDir>/.wiki-state.json`      — must be tracked, not ignored.
 *
 * "Tracked" is determined by `git ls-files --error-unmatch <path>` (exit 0).
 * "Ignored" is determined by `git check-ignore <path>` (exit 0).
 */
class WikiGitStateChecker(private val project: Project) {

    /** Expected status for one of the three checked files. */
    enum class Expectation { TRACKED, IGNORED }

    /** A discrepancy the user can be offered a fix for. */
    data class Issue(
        val relativePath: String,
        val expected: Expectation,
        val actuallyTracked: Boolean,
        val actuallyIgnored: Boolean,
        val fileExists: Boolean,
    ) {
        fun summary(): String = when (expected) {
            Expectation.TRACKED -> when {
                !fileExists -> "$relativePath: missing (should be committed)"
                actuallyIgnored -> "$relativePath: gitignored (should be committed)"
                !actuallyTracked -> "$relativePath: untracked (should be committed)"
                else -> "$relativePath: ok"
            }
            Expectation.IGNORED -> when {
                actuallyTracked -> "$relativePath: tracked (should be gitignored)"
                !actuallyIgnored && fileExists -> "$relativePath: not gitignored (should be)"
                else -> "$relativePath: ok"
            }
        }
    }

    /** Cheap entry point — returns empty list outside team mode or with no git repo. */
    fun check(): List<Issue> {
        if (!WikiLocator.getInstance(project).isTeamMode()) return emptyList()
        val basePath = project.basePath ?: return emptyList()
        val projectBase = Path.of(basePath)
        val repo = GitUtil.getRepositories(project).firstOrNull() ?: return emptyList()

        val wikiDir = WikiLocator.getInstance(project).wikiDir()
        val wikiStateRel = projectRelative(projectBase, wikiDir.resolve(WIKI_STATE_FILE)) ?: return emptyList()

        val targets = listOf(
            CONFIG_REL to Expectation.TRACKED,
            LOCAL_STATE_REL to Expectation.IGNORED,
            wikiStateRel to Expectation.TRACKED,
        )

        return targets.mapNotNull { (rel, expected) ->
            val abs = projectBase.resolve(rel)
            val tracked = isTracked(repo, rel)
            val ignored = isIgnored(repo, rel)
            val exists = Files.exists(abs)
            val ok = when (expected) {
                Expectation.TRACKED -> exists && tracked && !ignored
                Expectation.IGNORED -> ignored && !tracked
            }
            if (ok) null else Issue(
                relativePath = rel,
                expected = expected,
                actuallyTracked = tracked,
                actuallyIgnored = ignored,
                fileExists = exists,
            )
        }
    }

    /**
     * Apply the corrective action for [issue]. Returns true on success. Best-effort:
     * each fix is independent, callers may collect partial successes by re-running
     * [check] afterwards.
     */
    fun fix(issue: Issue): Boolean {
        val basePath = project.basePath
        if (basePath == null) {
            LOG.warn("fix($issue): project.basePath is null, aborting")
            return false
        }
        val projectBase = Path.of(basePath)
        val repo = GitUtil.getRepositories(project).firstOrNull()
        if (repo == null) {
            LOG.warn("fix($issue): no git repository at $projectBase, aborting")
            return false
        }
        // Git operations are relative to repo.root, not project.basePath. When
        // the project root coincides with the repo root, these are equal —
        // log the discrepancy when they don't so the failure mode is obvious.
        val repoRootStr = repo.root.path
        if (repoRootStr != basePath) {
            LOG.warn("fix($issue): repo root $repoRootStr != project base $basePath; relative path may be wrong")
        }

        val ok = when (issue.expected) {
            Expectation.TRACKED -> fixShouldBeTracked(projectBase, repo, issue)
            Expectation.IGNORED -> fixShouldBeIgnored(projectBase, repo, issue)
        }
        LOG.info("fix($issue) -> $ok")
        if (ok) {
            // Force IntelliJ's VCS layer to re-read the index so the Project View
            // file-status colors and right-click "Git…" menu reflect the change
            // we just made out-of-band. Without this, git4idea's cached Change
            // for this file persists until the next project-wide rescan tick.
            refreshVcsState(projectBase, issue.relativePath)
            // The .gitignore file itself may have grown or shrunk a line; refresh
            // it too so its own status is consistent.
            refreshVcsState(projectBase, ".gitignore")
        }
        return ok
    }

    private fun refreshVcsState(projectBase: Path, relativePath: String) {
        val absPath = projectBase.resolve(relativePath).toString()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath)
                if (vFile != null) {
                    VcsDirtyScopeManager.getInstance(project).fileDirty(vFile)
                    GitRepositoryManager.getInstance(project).getRepositoryForFile(vFile)?.update()
                } else {
                    // File missing on disk (e.g. after a future "delete" fix path) — fall back
                    // to a repo-wide refresh so VCS still notices the change.
                    GitRepositoryManager.getInstance(project).repositories.forEach { it.update() }
                }
            } catch (t: Throwable) {
                LOG.warn("VCS refresh failed for $relativePath: ${t.message}")
            }
        }
    }

    private fun fixShouldBeTracked(projectBase: Path, repo: GitRepository, issue: Issue): Boolean {
        if (!issue.fileExists) {
            LOG.info("WikiGitStateChecker: cannot stage missing file ${issue.relativePath}")
            return false
        }
        if (issue.actuallyIgnored) {
            // Drop the gitignore line that's hiding it. Idempotent if the line is absent.
            removeGitignoreLine(projectBase, issue.relativePath)
        }
        val handler = GitLineHandler(project, repo.root, GitCommand.ADD)
        // `--force` covers the case where check-ignore matched but the user removed
        // the line a beat ago and the in-memory excludes cache still bites.
        handler.addParameters("--force", "--", issue.relativePath)
        handler.setSilent(true)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) {
            LOG.warn("git add --force ${issue.relativePath} failed: exitCode=${result.exitCode} err=${result.errorOutputAsJoinedString}")
        }
        return result.success()
    }

    private fun fixShouldBeIgnored(projectBase: Path, repo: GitRepository, issue: Issue): Boolean {
        if (issue.actuallyTracked) {
            val rm = GitLineHandler(project, repo.root, GitCommand.RM)
            // `--force` is required when the working tree / index differs from HEAD —
            // otherwise git refuses with "use -f to force removal". We're explicitly
            // dropping this from tracking on purpose, so divergence is expected.
            rm.addParameters("--cached", "--force", "--", issue.relativePath)
            rm.setSilent(true)
            val rmResult = Git.getInstance().runCommand(rm)
            if (!rmResult.success()) {
                LOG.warn("git rm --cached ${issue.relativePath} failed: exitCode=${rmResult.exitCode} err=${rmResult.errorOutputAsJoinedString}")
                return false
            }
        }
        if (!issue.actuallyIgnored) {
            WikiRelocateHandler.appendGitignore(projectBase, issue.relativePath)
        }
        return true
    }

    private fun isTracked(repo: GitRepository, relativePath: String): Boolean {
        val handler = GitLineHandler(project, repo.root, GitCommand.LS_FILES)
        handler.addParameters("--error-unmatch", "--", relativePath)
        handler.setSilent(true)
        return Git.getInstance().runCommand(handler).success()
    }

    private fun isIgnored(repo: GitRepository, relativePath: String): Boolean {
        val handler = GitLineHandler(project, repo.root, GitCommand.CHECK_IGNORE)
        handler.addParameters("--", relativePath)
        handler.setSilent(true)
        // check-ignore exits 0 when the path IS ignored, 1 when it is not.
        return Git.getInstance().runCommand(handler).exitCode == 0
    }

    private fun projectRelative(projectBase: Path, target: Path): String? = runCatching {
        val rel = projectBase.relativize(target).toString().replace(java.io.File.separatorChar, '/')
        rel.ifBlank { null }
    }.getOrNull()

    companion object {
        private val LOG = Logger.getInstance(WikiGitStateChecker::class.java)

        const val CONFIG_REL = ".clawdea/config.json"
        const val LOCAL_STATE_REL = ".clawdea/wiki-state.local.json"
        const val WIKI_STATE_FILE = ".wiki-state.json"

        fun getInstance(project: Project): WikiGitStateChecker = WikiGitStateChecker(project)

        /**
         * Removes the first occurrence of [entry] from `<projectBase>/.gitignore`,
         * preserving every other line verbatim. No-op if the file is missing or
         * the entry is absent. Mirrors the contract of
         * [WikiRelocateHandler.appendGitignore] in reverse.
         */
        fun removeGitignoreLine(projectBase: Path, entry: String) {
            val gitignore = projectBase.resolve(".gitignore")
            if (!Files.isRegularFile(gitignore)) return
            val original = Files.readString(gitignore)
            // Preserve trailing-newline shape: split on '\n' and rebuild verbatim.
            val parts = original.split("\n").toMutableList()
            val idx = parts.indexOfFirst { it.trim() == entry }
            if (idx < 0) return
            parts.removeAt(idx)
            Files.writeString(gitignore, parts.joinToString("\n"))
        }
    }
}
