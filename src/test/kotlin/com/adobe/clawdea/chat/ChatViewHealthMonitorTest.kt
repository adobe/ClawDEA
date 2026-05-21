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

import java.awt.Rectangle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewHealthMonitorTest {

    @Test
    fun `isSuspendGap returns false when elapsed equals tick interval`() {
        assertFalse(ChatViewHealthMonitor.isSuspendGap(2000L, 2000L, 28000L))
    }

    @Test
    fun `isSuspendGap returns false for small jitter over interval`() {
        // Normal GC pause / scheduling jitter — nowhere near the threshold.
        assertFalse(ChatViewHealthMonitor.isSuspendGap(5000L, 2000L, 28000L))
    }

    @Test
    fun `isSuspendGap returns true for a one minute suspend`() {
        assertTrue(ChatViewHealthMonitor.isSuspendGap(60_000L, 2000L, 28000L))
    }

    @Test
    fun `isSuspendGap returns true at the exact threshold boundary`() {
        // tickInterval + threshold = 2000 + 28000 = 30000; boundary is inclusive.
        assertTrue(ChatViewHealthMonitor.isSuspendGap(30_000L, 2000L, 28000L))
    }

    private fun snapshot(
        deviceId: String = "Display0",
        scaleX: Double = 2.0,
        scaleY: Double = 2.0,
        bounds: Rectangle = Rectangle(0, 0, 1920, 1080),
    ) = ChatViewHealthMonitor.DisplaySnapshot(deviceId, scaleX, scaleY, bounds)

    @Test
    fun `isDisplayChanged returns false on first sample`() {
        // No previous snapshot yet — we shouldn't trigger a recovery on the
        // very first heartbeat after the panel is shown.
        assertFalse(ChatViewHealthMonitor.isDisplayChanged(null, snapshot()))
    }

    @Test
    fun `isDisplayChanged returns false when snapshots are identical`() {
        val s = snapshot()
        assertFalse(ChatViewHealthMonitor.isDisplayChanged(s, s.copy()))
    }

    @Test
    fun `isDisplayChanged detects DPI scale change`() {
        // Drag from a 2x retina display to a 1x external monitor.
        val before = snapshot(scaleX = 2.0, scaleY = 2.0)
        val after = snapshot(scaleX = 1.0, scaleY = 1.0)
        assertTrue(ChatViewHealthMonitor.isDisplayChanged(before, after))
    }

    @Test
    fun `isDisplayChanged detects monitor switch by device id`() {
        // Same DPI, same bounds, different physical screen.
        val before = snapshot(deviceId = "BuiltInDisplay")
        val after = snapshot(deviceId = "ExternalDisplay")
        assertTrue(ChatViewHealthMonitor.isDisplayChanged(before, after))
    }

    @Test
    fun `isDisplayChanged detects bounds change`() {
        // Plugging a monitor on the side of the laptop shifts the
        // GraphicsConfiguration bounds even when the panel stays on the
        // built-in display.
        val before = snapshot(bounds = Rectangle(0, 0, 1920, 1080))
        val after = snapshot(bounds = Rectangle(1920, 0, 2560, 1440))
        assertTrue(ChatViewHealthMonitor.isDisplayChanged(before, after))
    }
}
