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
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

@Service(Service.Level.PROJECT)
class DriftDetectionService(private val project: Project) {

    private val mutex = Object()
    private var lastEvents: List<DriftEvent> = emptyList()
    private var lastApplied: List<DriftEvent> = emptyList()
    private val listeners = mutableListOf<(events: List<DriftEvent>, applied: List<DriftEvent>) -> Unit>()

    /** Run detectors, filter dismissed, optionally auto-apply, store + notify. */
    fun rescan(runDreamScan: Boolean = false): Pair<List<DriftEvent>, List<DriftEvent>> = synchronized(mutex) {
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
        val collection = collectRaw(
            projectRoot = Paths.get(basePath),
            claudeDir = claudeDir,
            beforeState = state,
            settingsState = settings,
            now = Instant.now(),
            runDreamScan = runDreamScan,
        )
        val filtered = filterDismissed(collection.events, collection.newState)
        val (remaining, applied) = applyAndDismiss(filtered, settings.autoUpdateWiki, collection.newState, DriftAutoApplier.todayIso())
        if (applied.newState != state) {
            DriftStateStore.write(claudeDir, applied.newState)
        }
        lastEvents = remaining
        lastApplied = applied.events
        notifyListeners()
        return remaining to applied.events
    }

    fun runDreamScanNow(): Pair<List<DriftEvent>, List<DriftEvent>> = rescan(runDreamScan = true)

    fun current(): List<DriftEvent> = synchronized(mutex) { lastEvents }
    fun lastAppliedEvents(): List<DriftEvent> = synchronized(mutex) { lastApplied }

    fun recordProbeMiss(query: String, pathTokens: List<String>, hits: Int, contextHash: String) {
        val basePath = project.basePath ?: return
        val claudeDir = Paths.get(basePath).resolve(".claude")
        val miss = ProbeMiss(
            query = query,
            pathTokens = pathTokens,
            hits = hits,
            contextHash = contextHash,
            recordedAt = Instant.now().toString(),
        )
        DriftStateStore.update(claudeDir) { state ->
            val updated = state.probeMisses + miss
            state.copy(probeMisses = updated.takeLast(DriftState.MAX_PROBE_MISSES))
        }
    }

    fun recordUserCorrection(correctionSummary: String, contextHash: String) {
        val basePath = project.basePath ?: return
        val claudeDir = Paths.get(basePath).resolve(".claude")
        val correction = UserCorrectionRecord(
            summary = correctionSummary.take(500),
            contextHash = contextHash,
            recordedAt = Instant.now().toString(),
        )
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

    companion object {
        private val LOG = Logger.getInstance(DriftDetectionService::class.java)

        internal data class RawCollection(val events: List<DriftEvent>, val newState: DriftState)

        internal fun interface DreamDetectionRunner {
            fun detect(
                projectRoot: Path,
                state: DriftState,
                settings: DreamWikiSettings,
                now: Instant,
                force: Boolean,
                activeTurn: Boolean,
            ): DreamDetectionResult
        }

        internal fun collectRaw(
            projectRoot: Path,
            claudeDir: Path,
            beforeState: DriftState,
            settingsState: ClawDEASettings.State,
            now: Instant,
            runDreamScan: Boolean = false,
            detectDreams: DreamDetectionRunner = DreamDetectionRunner { root, state, settings, instant, force, activeTurn ->
                DreamWikiDetector().detect(
                    projectRoot = root,
                    state = state,
                    settings = settings,
                    now = instant,
                    force = force,
                    activeTurn = activeTurn,
                )
            },
        ): RawCollection {
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
            out += beforeState.suggestions

            // Dream wiki maintenance is being removed (Task 12 of the wiki
            // maintenance redesign). The dream-scan helpers that referenced
            // state.dream* fields have been stripped; rescan only updates
            // lastScanAt going forward.
            return RawCollection(
                events = out,
                newState = beforeState.copy(lastScanAt = now.toString()),
            )
        }

        internal fun isDreamFilesystemLockHeld(claudeDir: Path, now: Instant): Boolean {
            // Stub: removed in Task 12 alongside the rest of the dream pipeline.
            return false
        }

        internal fun filterDismissed(raw: List<DriftEvent>, state: DriftState): List<DriftEvent> {
            val dismissed = state.dismissed.toSet()
            return raw.filterNot { it.signature in dismissed }
        }

        data class ApplyResult(val events: List<DriftEvent>, val newState: DriftState)

        internal fun applyAndDismiss(
            events: List<DriftEvent>,
            autoUpdateEnabled: Boolean,
            beforeState: DriftState,
            today: String,
        ): Pair<List<DriftEvent>, ApplyResult> {
            if (!autoUpdateEnabled) return events to ApplyResult(emptyList(), beforeState)
            val applied = DriftAutoApplier.apply(events, today)
            val remaining = events.filterNot { it in applied }
            val newState = beforeState.copy(
                dismissed = beforeState.dismissed + applied.map { it.signature },
            )
            return remaining to ApplyResult(applied, newState)
        }

    }
}
