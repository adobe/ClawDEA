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

@Service(Service.Level.PROJECT)
class DriftDetectionService(private val project: Project) {

    private val mutex = Object()
    private var lastEvents: List<DriftEvent> = emptyList()
    private var lastApplied: List<DriftEvent> = emptyList()
    private val listeners = mutableListOf<(events: List<DriftEvent>, applied: List<DriftEvent>) -> Unit>()

    /** Run detectors, filter dismissed, optionally auto-apply, store + notify. */
    fun rescan(): Pair<List<DriftEvent>, List<DriftEvent>> = synchronized(mutex) {
        val basePath = project.basePath
        if (basePath == null) {
            lastEvents = emptyList()
            lastApplied = emptyList()
            notifyListeners()
            return emptyList<DriftEvent>() to emptyList()
        }
        val claudeDir = Paths.get(basePath).resolve(".claude")
        val raw = collectRaw(Paths.get(basePath), claudeDir)
        val state = DriftStateStore.read(claudeDir)
        val filtered = filterDismissed(raw, state)
        val autoUpdate = ClawDEASettings.getInstance().state.autoUpdateWiki
        val (remaining, applied) = applyAndDismiss(filtered, autoUpdate, state, DriftAutoApplier.todayIso())
        if (autoUpdate && applied.events.isNotEmpty()) {
            DriftStateStore.write(claudeDir, applied.newState)
        }
        lastEvents = remaining
        lastApplied = applied.events
        notifyListeners()
        return remaining to applied.events
    }

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

    private fun collectRaw(projectRoot: Path, claudeDir: Path): List<DriftEvent> {
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
        return out
    }

    companion object {
        private val LOG = Logger.getInstance(DriftDetectionService::class.java)

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
