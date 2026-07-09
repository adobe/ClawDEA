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
 * Pure gap-detection helpers for the chat-view heartbeat. The actual ticking
 * loop lives inline in ChatPanel; this object exists to hold the constants
 * and the unit-testable predicates.
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

    /**
     * Snapshot of the display state we track between heartbeat ticks. We
     * compare the [scaleX]/[scaleY] (DPI), the screen [bounds], and the
     * [deviceId] of the GraphicsDevice the chat panel is currently shown on.
     *
     * Any change between ticks indicates the user (un)plugged a monitor,
     * dragged the IDE window between displays with different DPI, or
     * resumed onto a different display layout than they slept on. JCEF's
     * OSR backing surface does not pick those changes up on its own, and
     * soft repaint kicks do not recover a frozen surface — the browser has
     * to be torn down and recreated, which ChatPanel.recreateBrowserAndReplay
     * does (issue #36).
     */
    data class DisplaySnapshot(
        val deviceId: String,
        val scaleX: Double,
        val scaleY: Double,
        val bounds: java.awt.Rectangle,
    )

    /**
     * True when [previous] and [current] disagree on any field that affects
     * how CEF should render: screen identity, DPI scale, or screen bounds.
     * A null [previous] means "first sample, no change yet".
     */
    fun isDisplayChanged(previous: DisplaySnapshot?, current: DisplaySnapshot): Boolean {
        if (previous == null) return false
        return previous != current
    }
}
