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
}
