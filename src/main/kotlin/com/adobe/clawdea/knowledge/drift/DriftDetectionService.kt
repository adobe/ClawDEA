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

import com.adobe.clawdea.knowledge.wiki.WikiLocator
import com.adobe.clawdea.knowledge.workspace.WorkspaceDiscovery
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

@Service(Service.Level.PROJECT)
class DriftDetectionService(private val project: Project) {

    private val mutex = Object()
    private var lastEvents: List<DriftEvent> = emptyList()
    private var lastApplied: List<DriftEvent> = emptyList()
    private val listeners = mutableListOf<(events: List<DriftEvent>, applied: List<DriftEvent>) -> Unit>()

    fun rescan(): Pair<List<DriftEvent>, List<DriftEvent>> = synchronized(mutex) {
        val basePath = project.basePath
        if (basePath == null) {
            lastEvents = emptyList()
            lastApplied = emptyList()
            notifyListeners()
            return emptyList<DriftEvent>() to emptyList()
        }
        // Skip while a git operation is in progress (rebase, merge, cherry-pick,
        // revert). Writing `.wiki-state.json` mid-rebase dirties the working tree
        // and breaks `git rebase --continue` with "You have unstaged changes".
        if (isRepoBusy(project)) {
            return lastEvents to lastApplied
        }
        val projectBase = Paths.get(basePath)
        val wikiDir = WikiLocator.getInstance(project).wikiDir()
        val state = DriftStateStore.read(wikiDir = wikiDir, projectBase = projectBase)
        val settings = ClawDEASettings.getInstance().state
        val now = Instant.now()
        val raw = collectRaw(
            project = project,
            projectRoot = projectBase,
            wikiDir = wikiDir,
            beforeState = state,
            settingsState = settings,
            now = now,
        )
        val filtered = filterDismissed(raw, state)
        val invoker = buildInvoker(basePath)
        val (remaining, applied) = runBlocking {
            applyAndDismiss(filtered, settings.autoUpdateWiki, state, DriftAutoApplier.todayIso(), invoker)
        }
        val newState = applied.newState.copy(lastScanAt = now.toString())
        val headSha = currentHeadSha(project) ?: ""
        // Only advance lastSyncedCommit on first-run baseline or when wiki content
        // was actually authored. Advancing on every tick rewrites the team-shared
        // `.wiki-state.json` and creates a dirty file after every commit, which is
        // what makes `git rebase` (and `git stash pop`) refuse to start.
        val newStateWithSync = if (shouldAdvanceSync(state, applied)) {
            bumpSyncedCommit(newState, headSha)
        } else newState
        if (newStateWithSync != state) {
            DriftStateStore.write(wikiDir = wikiDir, projectBase = projectBase, state = newStateWithSync)
        }
        lastEvents = remaining
        lastApplied = applied.events
        notifyListeners()
        return remaining to applied.events
    }

    fun current(): List<DriftEvent> = synchronized(mutex) { lastEvents }
    fun lastAppliedEvents(): List<DriftEvent> = synchronized(mutex) { lastApplied }

    fun recordProbeMiss(query: String, pathTokens: List<String>, hits: Int, contextHash: String) {
        val basePath = project.basePath ?: return
        val projectBase = Paths.get(basePath)
        val wikiDir = WikiLocator.getInstance(project).wikiDir()
        val miss = ProbeMiss(query, pathTokens, hits, contextHash, Instant.now().toString())
        DriftStateStore.update(wikiDir = wikiDir, projectBase = projectBase) { state ->
            val updated = state.probeMisses + miss
            state.copy(probeMisses = updated.takeLast(DriftState.MAX_PROBE_MISSES))
        }
    }

    fun recordUserCorrection(correctionSummary: String, contextHash: String) {
        val basePath = project.basePath ?: return
        val projectBase = Paths.get(basePath)
        val wikiDir = WikiLocator.getInstance(project).wikiDir()
        val correction = UserCorrectionRecord(correctionSummary.take(500), contextHash, Instant.now().toString())
        DriftStateStore.update(wikiDir = wikiDir, projectBase = projectBase) { state ->
            val updated = state.userCorrections + correction
            state.copy(userCorrections = updated.takeLast(DriftState.MAX_USER_CORRECTIONS))
        }
    }

    fun dismiss(signature: String) {
        val basePath = project.basePath ?: return
        // Same in-progress-git guard as `rescan` — dismiss rewrites the
        // team-shared `.wiki-state.json` (it strips the signature from
        // `suggestions`), so it has the same dirty-tree hazard.
        if (isRepoBusy(project)) return
        val projectBase = Paths.get(basePath)
        val wikiDir = WikiLocator.getInstance(project).wikiDir()
        DriftStateStore.update(wikiDir = wikiDir, projectBase = projectBase) { state ->
            state.copy(
                dismissed = state.dismissed + signature,
                suggestions = state.suggestions.filterNot { it.signature == signature },
            )
        }
        rescan()
    }

    fun addListener(l: (events: List<DriftEvent>, applied: List<DriftEvent>) -> Unit): () -> Unit {
        synchronized(mutex) { listeners.add(l) }
        return { synchronized(mutex) { listeners.remove(l) } }
    }

    private fun notifyListeners() {
        val events = lastEvents
        val applied = lastApplied
        for (l in listeners.toList()) {
            try { l(events, applied) } catch (e: Throwable) { LOG.warn("listener threw: ${e.message}") }
        }
    }

    private fun currentHeadSha(project: Project): String? {
        val repo = git4idea.repo.GitRepositoryManager.getInstance(project)
            .repositories.firstOrNull() ?: return null
        return repo.currentRevision
    }

    private fun isRepoBusy(project: Project): Boolean {
        val repo = git4idea.repo.GitRepositoryManager.getInstance(project)
            .repositories.firstOrNull() ?: return false
        return repo.state != com.intellij.dvcs.repo.Repository.State.NORMAL
    }

    private fun buildInvoker(basePath: String): WikiAuthorInvoker {
        val settings = ClawDEASettings.getInstance()
        val cliPath = com.adobe.clawdea.cli.resolveClaudeCliPath(settings.state.cliPath)
        val mcpPort = com.adobe.clawdea.mcp.McpServer.getInstance(project).port
        val effectiveProvider = com.adobe.clawdea.auth.AuthManager.getInstance().effectiveProviderId()
        // Match the chat's behaviour: pass --model only if the user has
        // explicitly selected one for this project. Otherwise let the CLI
        // pick its built-in default (Opus 4.7 on the current Anthropic CLI).
        val modelId = settings.getCliModelId(basePath, effectiveProvider)
        return DefaultWikiAuthorInvoker(
            claudeCliPath = cliPath,
            projectRoot = Paths.get(basePath),
            mcpPort = mcpPort,
            modelId = modelId,
        )
    }

    companion object {
        private val LOG = Logger.getInstance(DriftDetectionService::class.java)

        data class ApplyResult(val events: List<DriftEvent>, val newState: DriftState)

        internal fun bumpSyncedCommit(state: DriftState, headSha: String): DriftState {
            if (headSha.isBlank()) return state
            return state.copy(lastSyncedCommit = headSha)
        }

        /**
         * `lastSyncedCommit` is "the commit the wiki currently describes". Advance
         * it only when one of these holds:
         *  - First run baseline: the field is blank — adopt current HEAD so the
         *    next scan has a reference point for the `lastSyncedCommit..HEAD`
         *    range. Without this the detector would report every commit forever.
         *  - Wiki content actually changed in this scan: at least one event was
         *    applied (deterministic auto-apply or wiki-author edit).
         *
         * Routine ticks where nothing was authored leave the SHA where it is,
         * which means `.wiki-state.json` only changes when the wiki content
         * itself changes. That keeps the team-shared file out of working-tree
         * diffs that would block `git rebase` / `git stash pop`.
         */
        internal fun shouldAdvanceSync(beforeState: DriftState, applied: ApplyResult): Boolean {
            if (beforeState.lastSyncedCommit.isBlank()) return true
            return applied.events.isNotEmpty()
        }

        internal fun collectRaw(
            project: Project,
            projectRoot: Path,
            wikiDir: Path,
            beforeState: DriftState,
            settingsState: ClawDEASettings.State,
            now: Instant,
        ): List<DriftEvent> {
            val out = mutableListOf<DriftEvent>()
            out += CodeRenameDetector.detect(
                wikiDir = wikiDir,
                sourceRoots = listOf(
                    projectRoot.resolve("src/main/kotlin"),
                    projectRoot.resolve("src/main/java"),
                ),
            )
            val manifestPath = WorkspaceDiscovery.discover(projectRoot)
            if (manifestPath != null) {
                out += ManifestStaleDetector.detect(manifestPath)
            }
            if (settingsState.enableWikiLibrarian) {
                out += CommitWikiDriftDetector.detect(
                    project = project,
                    wikiDir = wikiDir,
                    lastSyncedCommit = beforeState.lastSyncedCommit,
                    now = now,
                )
            }
            out += beforeState.suggestions
            return out
        }

        internal fun filterDismissed(raw: List<DriftEvent>, state: DriftState): List<DriftEvent> {
            val dismissed = state.dismissed.toSet()
            return raw.filterNot { it.signature in dismissed }
        }

        suspend fun applyAndDismiss(
            events: List<DriftEvent>,
            autoUpdateEnabled: Boolean,
            beforeState: DriftState,
            today: String,
            wikiAuthorInvoker: WikiAuthorInvoker,
        ): Pair<List<DriftEvent>, ApplyResult> {
            LOG.info("applyAndDismiss: events=${events.size} autoUpdate=$autoUpdateEnabled kinds=${events.groupingBy { it::class.simpleName }.eachCount()}")
            if (!autoUpdateEnabled || events.isEmpty()) {
                return events to ApplyResult(emptyList(), beforeState)
            }
            // Step 1: deterministic auto-apply (CodeRename + ManifestStale) — fast path.
            val deterministicApplied = DriftAutoApplier.apply(events, today)
            val afterDeterministic = events - deterministicApplied.toSet()

            // Step 2: route remainder through wiki-author.
            val needsAuthor = afterDeterministic.filter {
                it is DriftEvent.CommitDrift || it is DriftEvent.WikiSuggestion ||
                    (it is DriftEvent.CodeRename && it.suggestedReplacement == null) ||
                    (it is DriftEvent.ManifestStale)  // edge case: deterministic apply failed
            }
            val authoredAcked = if (needsAuthor.isNotEmpty()) {
                wikiAuthorInvoker.invoke(needsAuthor).actedOnSignatures
            } else emptySet()
            val authoredApplied = needsAuthor.filter { it.signature in authoredAcked }

            val applied = deterministicApplied + authoredApplied
            // When autoUpdateEnabled, hide wiki-author events from the banner:
            // they are being handled by the subagent, which will surface its own UI
            // (edit-review dialog, chat panel render) as needed. Don't re-surface
            // them in the "remaining" list that feeds the drift banner.
            val remaining = if (autoUpdateEnabled) {
                events - applied.toSet() - needsAuthor.toSet()
            } else {
                events - applied.toSet()
            }
            val newState = beforeState.copy(
                dismissed = beforeState.dismissed + applied.map { it.signature },
            )
            return remaining to ApplyResult(applied, newState)
        }
    }
}
