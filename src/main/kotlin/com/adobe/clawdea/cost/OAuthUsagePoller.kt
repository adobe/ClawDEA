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
package com.adobe.clawdea.cost

import com.adobe.clawdea.auth.AuthManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls oauth/usage on a ~5-minute cadence (subscription only) and feeds CostTracker.
 * On repeated failure, backs off (5m → up to 40m) and leaves the cached usage UNAVAILABLE
 * so the UI shows the notional estimate. Read-only. Stops when [parentDisposable] disposes.
 */
class OAuthUsagePoller(private val project: Project, parentDisposable: Disposable) {
    private val running = AtomicBoolean(true)
    @Volatile private var failures = 0

    init {
        Disposer.register(parentDisposable) { running.set(false) }
        ApplicationManager.getApplication().executeOnPooledThread { loop() }
    }

    private fun loop() {
        while (running.get()) {
            if (AuthManager.getInstance().effectiveProviderId() == "subscription") {
                val usage = OAuthUsageClient.fetch()
                if (project.isDisposed) return
                CostTracker.getInstance(project).updateUsage(usage)
                failures = if (usage.available) 0 else (failures + 1).coerceAtMost(3)
            }
            val baseMs = 5 * 60 * 1000L
            val waitMs = baseMs * (1L shl failures)  // 5m, 10m, 20m, 40m
            sleep(waitMs)
        }
    }

    /** Interruptible-ish sleep: wakes every 500ms to check the running flag. */
    private fun sleep(ms: Long) {
        val endMs = System.currentTimeMillis() + ms
        while (running.get() && System.currentTimeMillis() < endMs) {
            try { Thread.sleep(500) } catch (_: InterruptedException) { return }
        }
    }
}
