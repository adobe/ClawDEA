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
import com.intellij.util.Alarm
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically calls [DriftDetectionService.rescan] so persisted wiki suggestions
 * recorded between IDE events (project open, git change, /refresh-wiki, banner
 * dismissal) get picked up without manual action. Only runs when
 * `autoUpdateWiki` is enabled — the flag is checked on every tick so toggling
 * it on takes effect within one interval.
 *
 * Skips overlapping ticks: a slow rescan (wiki-author subprocess can take up
 * to 5 minutes) does not stack additional rescans behind the synchronized
 * mutex on `DriftDetectionService.rescan`.
 */
@Service(Service.Level.PROJECT)
class DriftPeriodicScanner(private val project: Project) {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
    private val running = AtomicBoolean(false)

    fun start() {
        scheduleNext()
    }

    private fun scheduleNext() {
        if (project.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ tick() }, INTERVAL_MS)
    }

    private fun tick() {
        try {
            if (project.isDisposed) return
            if (!ClawDEASettings.getInstance().state.autoUpdateWiki) return
            if (!running.compareAndSet(false, true)) {
                LOG.debug("periodic rescan skipped: previous tick still running")
                return
            }
            try {
                project.getService(DriftDetectionService::class.java).rescan()
            } finally {
                running.set(false)
            }
        } catch (e: Throwable) {
            LOG.warn("periodic drift rescan failed: ${e.message}")
        } finally {
            scheduleNext()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DriftPeriodicScanner::class.java)
        const val INTERVAL_MS = 60_000
    }
}

class DriftPeriodicScannerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(DriftPeriodicScanner::class.java).start()
    }
}
