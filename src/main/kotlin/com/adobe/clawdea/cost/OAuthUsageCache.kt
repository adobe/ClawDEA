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

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the latest subscription usage and fans it out to every open project's sink. There is
 * exactly ONE cache (owned by the application-level [OAuthUsageService]); the single shared poll
 * publishes into it and each project subscribes. This is what keeps projects from diverging — the
 * old design polled per-project, so a transient failure in one project flipped only that project
 * to UNAVAILABLE for up to 40 minutes while siblings kept showing usage.
 *
 * [subscribe] replays the latest cached value to the new sink immediately, so a project opening (or
 * recovering) mid-cycle shows usage at once instead of waiting for the next successful fetch.
 * Pure and headless-testable: no IDE, network, or threading dependencies.
 */
class OAuthUsageCache {
    private val sinks = CopyOnWriteArrayList<(SubscriptionUsage) -> Unit>()
    @Volatile private var latest: SubscriptionUsage = SubscriptionUsage.UNAVAILABLE

    /** Register [sink], replay the latest value to it now, and return an unsubscribe handle. */
    fun subscribe(sink: (SubscriptionUsage) -> Unit): () -> Unit {
        sinks.add(sink)
        sink(latest)
        return { sinks.remove(sink) }
    }

    /** Cache [usage] as the latest value and deliver it to every current subscriber. */
    fun publish(usage: SubscriptionUsage) {
        latest = usage
        sinks.forEach { it(usage) }
    }
}
