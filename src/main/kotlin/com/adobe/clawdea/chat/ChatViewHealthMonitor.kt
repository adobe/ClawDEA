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
package com.adobe.clawdea.chat

/**
 * Pure gap-detection helper for the chat-view heartbeat. The actual ticking
 * loop lives inline in ChatPanel; this object exists to hold the constants
 * and the unit-testable gap predicate.
 */
internal object ChatViewHealthMonitor {
    const val TICK_INTERVAL_MS: Long = 2000
    const val GAP_THRESHOLD_MS: Long = 28000

    /**
     * True when the elapsed wall-clock time between heartbeat ticks indicates
     * the JVM was suspended (e.g. laptop slept). Returns true on the boundary.
     */
    fun isSuspendGap(elapsedMs: Long, tickInterval: Long, threshold: Long): Boolean =
        elapsedMs >= tickInterval + threshold
}
