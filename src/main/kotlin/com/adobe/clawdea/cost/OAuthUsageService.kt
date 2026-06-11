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

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-level owner of the subscription oauth/usage poll. Exactly ONE poll loop runs for the
 * whole IDE (not one per open project), publishing into a single shared [OAuthUsageCache] that every
 * project's [CostTracker] subscribes to via [OAuthUsageProjectLink].
 *
 * This replaces the former per-project poller, whose N independent loops over one rate-limited
 * endpoint caused projects to diverge: a transient failure (timeout, momentary non-200, token-read
 * race) flipped only that project to UNAVAILABLE and then backed off for up to 40 minutes, while
 * sibling projects whose polls happened to succeed kept showing usage. With a single shared poll the
 * value is identical everywhere, and the cache replays it to any project that opens mid-cycle.
 *
 * On repeated failure, backs off (5m → up to 40m) and leaves the cached usage UNAVAILABLE so the UI
 * shows the notional estimate. Read-only.
 */
@Service(Service.Level.APP)
class OAuthUsageService : Disposable {
    val cache = OAuthUsageCache()
    private val running = AtomicBoolean(false)
    @Volatile private var failures = 0
    @Volatile private var lastAvailable = false

    /** Idempotent: starts the single polling loop once. Safe to call on every project open. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread { loop() }
    }

    override fun dispose() {
        running.set(false)
    }

    private fun loop() {
        while (running.get()) {
            // NOT gated on the active provider: a user on bedrock can still have a Claude
            // subscription, and we want its usage shown alongside. fetch() self-limits —
            // it returns UNAVAILABLE when no OAuth token is present (i.e. no subscription).
            // fetch() logs the granular cause (no-token / HTTP status / IO error) on failure.
            val usage = OAuthUsageClient.fetch()
            val wasAvailable = lastAvailable
            cache.publish(usage)
            failures = if (usage.available) 0 else (failures + 1).coerceAtMost(FAILURE_CAP)
            val waitMs = backoffMs(failures)
            // Log only on a state transition, so a healthy poll stays quiet but the flip to
            // "unavailable" (and the recovery) is visible without spamming the log every 5m.
            if (usage.available != wasAvailable) {
                if (usage.available) log.info("oauth/usage available; polling at 5m cadence")
                else log.info("oauth/usage unavailable; backing off ${waitMs / 60_000}m (failure #$failures)")
            }
            lastAvailable = usage.available
            sleep(waitMs)
        }
    }

    /** Interruptible-ish sleep: wakes every 500ms to check the running flag. */
    private fun sleep(ms: Long) {
        val endMs = System.currentTimeMillis() + ms
        while (running.get() && System.currentTimeMillis() < endMs) {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    companion object {
        private val log = logger<OAuthUsageService>()

        /** Failures past this point don't change the wait — the curve has already plateaued. */
        private const val FAILURE_CAP = 1

        /**
         * Wait before the next poll given the consecutive-[failures] count: 5m base, escalating
         * once to a 10m ceiling. With a single shared poll there's no fleet of projects to spare
         * the endpoint, so the old 40m ceiling only delayed recovery — we hold at 10m instead.
         */
        fun backoffMs(failures: Int): Long {
            val baseMs = 5 * 60 * 1000L
            return baseMs * (1L shl failures.coerceIn(0, FAILURE_CAP)) // 5m, then 10m
        }

        fun getInstance(): OAuthUsageService =
            ApplicationManager.getApplication().getService(OAuthUsageService::class.java)
    }
}
