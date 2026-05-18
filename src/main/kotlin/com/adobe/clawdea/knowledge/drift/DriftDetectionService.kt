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
        val claudeDir = Paths.get(basePath).resolve(".claude")
        val state = DriftStateStore.read(claudeDir)
        val settings = ClawDEASettings.getInstance().state
        val now = Instant.now()
        val raw = collectRaw(
            project = project,
            projectRoot = Paths.get(basePath),
            claudeDir = claudeDir,
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
        if (newState != state) {
            DriftStateStore.write(claudeDir, newState)
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
        val claudeDir = Paths.get(basePath).resolve(".claude")
        val miss = ProbeMiss(query, pathTokens, hits, contextHash, Instant.now().toString())
        DriftStateStore.update(claudeDir) { state ->
            val updated = state.probeMisses + miss
            state.copy(probeMisses = updated.takeLast(DriftState.MAX_PROBE_MISSES))
        }
    }

    fun recordUserCorrection(correctionSummary: String, contextHash: String) {
        val basePath = project.basePath ?: return
        val claudeDir = Paths.get(basePath).resolve(".claude")
        val correction = UserCorrectionRecord(correctionSummary.take(500), contextHash, Instant.now().toString())
        DriftStateStore.update(claudeDir) { state ->
            val updated = state.userCorrections + correction
            state.copy(userCorrections = updated.takeLast(DriftState.MAX_USER_CORRECTIONS))
        }
    }

    fun dismiss(signature: String) {
        val basePath = project.basePath ?: return
        val claudeDir = Paths.get(basePath).resolve(".claude")
        DriftStateStore.update(claudeDir) { state ->
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

        internal fun collectRaw(
            project: Project,
            projectRoot: Path,
            claudeDir: Path,
            beforeState: DriftState,
            settingsState: ClawDEASettings.State,
            now: Instant,
        ): List<DriftEvent> {
            val out = mutableListOf<DriftEvent>()
            val wikiDir = claudeDir.resolve("wiki")
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
                    lastScanAt = parseInstantOrNull(beforeState.lastScanAt),
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

        private fun parseInstantOrNull(text: String): Instant? = try {
            if (text.isBlank()) null else Instant.parse(text)
        } catch (_: Exception) { null }
    }
}
