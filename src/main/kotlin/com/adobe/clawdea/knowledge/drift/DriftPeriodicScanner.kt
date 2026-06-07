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

import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Periodically calls [DriftDetectionService.rescan] so persisted wiki suggestions
 * recorded between IDE events (project open, git change, /refresh-wiki, banner
 * dismissal) get picked up without manual action. Only runs when
 * `autoUpdateWiki` is enabled — the flag is checked on every tick so toggling
 * it on takes effect within one interval.
 *
 * Runs as a single sequential coroutine loop on the service's intrinsic scope:
 * each tick awaits the previous rescan before the next interval starts, so a
 * slow rescan (wiki-author subprocess can take up to 5 minutes) never stacks
 * overlapping rescans. `rescan` is a suspend function, so the loop never blocks
 * a thread while the subprocess runs.
 */
@Service(Service.Level.PROJECT)
class DriftPeriodicScanner(private val project: Project, private val scope: CoroutineScope) {

    @Volatile private var loop: Job? = null

    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(INTERVAL_MS)
                tick()
            }
        }
    }

    private suspend fun tick() {
        try {
            if (project.isDisposed) return
            if (!ClawDEASettings.getInstance().state.autoUpdateWiki) return
            // Run the rescan NonCancellable so a scope cancellation (or any future
            // canceller) can't abort a rescan that has already launched the
            // wiki-author subprocess and leave the suggestion half-authored. The
            // surrounding loop still stops on cancellation at the next `delay`.
            withContext(NonCancellable) {
                project.getService(DriftDetectionService::class.java).rescan()
            }
        } catch (e: Throwable) {
            LOG.warn("periodic drift rescan failed: ${e.message}")
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DriftPeriodicScanner::class.java)
        const val INTERVAL_MS = 60_000L
    }
}

class DriftPeriodicScannerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(DriftPeriodicScanner::class.java).start()
    }
}
