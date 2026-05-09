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

    fun dismiss(signature: String) {
        val basePath = project.basePath ?: return
        val claudeDir = Paths.get(basePath).resolve(".claude")
        DriftStateStore.update(claudeDir) { it.copy(dismissed = it.dismissed + signature) }
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

            val dreamResult = if (runDreamScan) {
                detectDreamEvents(projectRoot, beforeState, settingsState, now, detectDreams)
            } else {
                cheapDreamDueCheck(beforeState, settingsState, now)
            }
            out += dreamResult.events

            return RawCollection(
                events = out,
                newState = updateDreamState(beforeState, dreamResult, now),
            )
        }

        private fun detectDreamEvents(
            projectRoot: Path,
            state: DriftState,
            settingsState: ClawDEASettings.State,
            now: Instant,
            detectDreams: DreamDetectionRunner,
        ): DreamDetectionResult {
            if (state.dreamLockOwner.isNotBlank()) {
                return DreamDetectionResult(
                    events = emptyList(),
                    status = "not-run:lock-held",
                    filteredCandidateCount = state.dreamFilteredCandidateCount,
                    attempted = false,
                    successful = false,
                )
            }
            val settings = DreamWikiSettings(
                enabled = settingsState.enableKnowledgeLayer && settingsState.enableDreamWikiMaintenance,
                minElapsedHours = settingsState.dreamWikiMinElapsedHours,
                minSignalUnits = settingsState.dreamWikiMinSignalUnits,
                scanThrottleMinutes = settingsState.dreamWikiScanThrottleMinutes,
            )
            return try {
                detectDreams.detect(
                    projectRoot = projectRoot,
                    state = state,
                    settings = settings,
                    now = now,
                    force = true,
                    activeTurn = false,
                )
            } catch (e: Throwable) {
                LOG.warn("Dream wiki detection failed: ${e.message}")
                DreamDetectionResult(
                    events = emptyList(),
                    status = "error:${e.javaClass.simpleName}:${e.message.orEmpty()}",
                    filteredCandidateCount = 0,
                    attempted = true,
                    successful = false,
                )
            }
        }

        private fun cheapDreamDueCheck(
            state: DriftState,
            settingsState: ClawDEASettings.State,
            now: Instant,
        ): DreamDetectionResult {
            val settings = DreamWikiSettings(
                enabled = settingsState.enableKnowledgeLayer && settingsState.enableDreamWikiMaintenance,
                minElapsedHours = settingsState.dreamWikiMinElapsedHours,
                minSignalUnits = settingsState.dreamWikiMinSignalUnits,
                scanThrottleMinutes = settingsState.dreamWikiScanThrottleMinutes,
            )
            val decision = DreamDueGate.evaluate(
                enabled = settings.enabled,
                now = now,
                state = state,
                minElapsedHours = settings.minElapsedHours,
                minSignalUnits = settings.minSignalUnits,
                scanThrottleMinutes = settings.scanThrottleMinutes,
                activeTurn = false,
                lockHeld = state.dreamLockOwner.isNotBlank(),
            )
            return DreamDetectionResult(
                events = emptyList(),
                status = if (decision.due) "due" else "not-due:${decision.reasons.joinToString(",")}",
                filteredCandidateCount = state.dreamFilteredCandidateCount,
                attempted = false,
                successful = false,
            )
        }

        private fun updateDreamState(
            beforeState: DriftState,
            result: DreamDetectionResult,
            now: Instant,
        ): DriftState {
            val nowText = now.toString()
            return beforeState.copy(
                dreamLastRunAt = if (result.attempted) nowText else beforeState.dreamLastRunAt,
                dreamLastDueCheckAt = nowText,
                dreamLastStatus = result.status,
                dreamLastSuccessfulScanAt = if (result.successful) nowText else beforeState.dreamLastSuccessfulScanAt,
                dreamLastFailedScanAt = if (result.attempted && !result.successful) nowText else beforeState.dreamLastFailedScanAt,
                dreamProcessedSignalUnits = if (result.successful) {
                    beforeState.dreamObservedSignalUnits
                } else {
                    beforeState.dreamProcessedSignalUnits
                },
                dreamFilteredCandidateCount = result.filteredCandidateCount,
            )
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
