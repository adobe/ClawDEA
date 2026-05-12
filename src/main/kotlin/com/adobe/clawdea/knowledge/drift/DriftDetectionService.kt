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
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Duration
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

            val dreamState = if (runDreamScan) {
                beforeState
            } else {
                observeCheapDreamSignals(claudeDir, beforeState, out)
            }
            val dreamResult = if (runDreamScan) {
                detectDreamEvents(projectRoot, claudeDir, dreamState, settingsState, now, detectDreams)
            } else {
                cheapDreamDueCheck(claudeDir, dreamState, settingsState, now)
            }
            out += dreamResult.events

            return RawCollection(
                events = out,
                newState = updateDreamState(dreamState, dreamResult, now).copy(
                    lastScanAt = now.toString(),
                ),
            )
        }

        private fun detectDreamEvents(
            projectRoot: Path,
            claudeDir: Path,
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
            if (!settingsState.enableKnowledgeLayer || !settingsState.enableDreamWikiMaintenance) {
                return DreamDetectionResult(
                    events = emptyList(),
                    status = "not-run:disabled",
                    filteredCandidateCount = state.dreamFilteredCandidateCount,
                    attempted = false,
                    successful = false,
                )
            }
            val lock = acquireDreamLock(claudeDir, now) ?: return DreamDetectionResult(
                events = emptyList(),
                status = "not-run:lock-held",
                filteredCandidateCount = state.dreamFilteredCandidateCount,
                attempted = false,
                successful = false,
            )
            val settings = DreamWikiSettings(
                enabled = settingsState.enableKnowledgeLayer && settingsState.enableDreamWikiMaintenance,
                minElapsedHours = settingsState.dreamWikiMinElapsedHours,
                minSignalUnits = settingsState.dreamWikiMinSignalUnits,
                scanThrottleMinutes = settingsState.dreamWikiScanThrottleMinutes,
            )
            return try {
                detectDreams.detect(
                    projectRoot = projectRoot,
                    state = state.copy(dreamLockOwner = lock.owner, dreamLockAcquiredAt = now.toString()),
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
            } finally {
                releaseDreamLock(lock)
            }
        }

        private fun cheapDreamDueCheck(
            claudeDir: Path,
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
                lockHeld = state.dreamLockOwner.isNotBlank() || isDreamFilesystemLockHeld(claudeDir, now),
            )
            return DreamDetectionResult(
                events = emptyList(),
                status = if (decision.due) "due" else "not-due:${decision.reasons.joinToString(",")}",
                filteredCandidateCount = state.dreamFilteredCandidateCount,
                attempted = false,
                successful = false,
            )
        }

        private fun observeCheapDreamSignals(
            claudeDir: Path,
            state: DriftState,
            rawEvents: List<DriftEvent>,
        ): DriftState {
            val lastSuccessfulScan = parseInstantOrNull(state.dreamLastSuccessfulScanAt)
            val signalUnits = (
                countRelevantFileSignals(claudeDir, lastSuccessfulScan) +
                    countRawDriftSignals(rawEvents, lastSuccessfulScan)
                ).coerceAtMost(MAX_CHEAP_SIGNAL_UNITS)
            if (signalUnits <= 0) return state
            val observed = maxOf(
                state.dreamObservedSignalUnits,
                (state.dreamProcessedSignalUnits + signalUnits).coerceAtMost(MAX_OBSERVED_SIGNAL_UNITS),
            )
            return state.copy(dreamObservedSignalUnits = observed)
        }

        private fun countRelevantFileSignals(claudeDir: Path, lastSuccessfulScan: Instant?): Int {
            var count = 0
            if (changedSince(claudeDir.resolve("REPO_STATE.md"), lastSuccessfulScan)) count++
            if (changedSince(claudeDir.resolve("notes").resolve("CURRENT.md"), lastSuccessfulScan)) count++

            val wikiDir = claudeDir.resolve("wiki")
            if (Files.isDirectory(wikiDir)) {
                val stream = Files.walk(wikiDir)
                try {
                    count += stream
                        .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".md") }
                        .filter { path -> changedSince(path, lastSuccessfulScan) }
                        .limit(MAX_CHEAP_SIGNAL_UNITS.toLong())
                        .count()
                        .toInt()
                } finally {
                    stream.close()
                }
            }
            return count.coerceAtMost(MAX_CHEAP_SIGNAL_UNITS)
        }

        private fun countRawDriftSignals(rawEvents: List<DriftEvent>, lastSuccessfulScan: Instant?): Int =
            rawEvents.count { event ->
                when (event) {
                    is DriftEvent.CodeRename -> changedSince(event.wikiPage, lastSuccessfulScan)
                    is DriftEvent.ManifestStale -> changedSince(event.manifestPath, lastSuccessfulScan)
                    else -> false
                }
            }.coerceAtMost(MAX_CHEAP_SIGNAL_UNITS)

        private fun changedSince(path: Path, lastSuccessfulScan: Instant?): Boolean {
            if (!Files.isRegularFile(path)) return false
            if (lastSuccessfulScan == null) return true
            return try {
                Files.getLastModifiedTime(path).toInstant().isAfter(lastSuccessfulScan)
            } catch (_: Exception) {
                false
            }
        }

        private fun parseInstantOrNull(text: String): Instant? =
            try {
                Instant.parse(text)
            } catch (_: Exception) {
                null
            }

        private data class DreamLock(val file: Path, val owner: String, val content: String)

        private fun acquireDreamLock(claudeDir: Path, now: Instant): DreamLock? {
            val wikiDir = claudeDir.resolve("wiki")
            Files.createDirectories(wikiDir)
            val file = wikiDir.resolve(DREAM_LOCK_FILE)
            return tryCreateDreamLock(file, now) ?: if (deleteStaleDreamLock(file, now)) {
                tryCreateDreamLock(file, now)
            } else {
                null
            }
        }

        private fun tryCreateDreamLock(file: Path, now: Instant): DreamLock? {
            val owner = "pid:${ProcessHandle.current().pid()}:thread:${Thread.currentThread().threadId()}"
            val content = "$owner\n$now"
            return try {
                Files.writeString(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                DreamLock(file, owner, content)
            } catch (_: FileAlreadyExistsException) {
                null
            }
        }

        private fun deleteStaleDreamLock(file: Path, now: Instant): Boolean {
            val acquiredAt = readDreamLockAcquiredAt(file) ?: return false
            if (Duration.between(acquiredAt, now) <= DREAM_LOCK_STALE_AFTER) return false
            return try {
                Files.deleteIfExists(file)
            } catch (_: Exception) {
                false
            }
        }

        internal fun isDreamFilesystemLockHeld(claudeDir: Path, now: Instant): Boolean {
            val file = claudeDir.resolve("wiki").resolve(DREAM_LOCK_FILE)
            if (!Files.exists(file)) return false
            val acquiredAt = readDreamLockAcquiredAt(file) ?: return true
            return Duration.between(acquiredAt, now) <= DREAM_LOCK_STALE_AFTER
        }

        private fun readDreamLockAcquiredAt(file: Path): Instant? =
            try {
                Files.readString(file).lineSequence().drop(1).firstOrNull()?.let(Instant::parse)
            } catch (_: Exception) {
                null
            }

        private fun releaseDreamLock(lock: DreamLock) {
            try {
                if (Files.exists(lock.file) && Files.readString(lock.file) == lock.content) {
                    Files.deleteIfExists(lock.file)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to release Dream wiki lock ${lock.file}: ${e.message}")
            }
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
                dreamLockOwner = "",
                dreamLockAcquiredAt = "",
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

        private const val DREAM_LOCK_FILE = ".dream.lock"
        private const val MAX_CHEAP_SIGNAL_UNITS = 100
        private const val MAX_OBSERVED_SIGNAL_UNITS = 10_000
        private val DREAM_LOCK_STALE_AFTER: Duration = Duration.ofHours(6)
    }
}
