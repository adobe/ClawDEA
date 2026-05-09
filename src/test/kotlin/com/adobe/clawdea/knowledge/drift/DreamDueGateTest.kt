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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DreamDueGateTest {

    private val now = Instant.parse("2026-05-09T12:00:00Z")

    @Test fun `not due when dream maintenance is disabled`() {
        val decision = DreamDueGate.evaluate(
            enabled = false,
            now = now,
            state = readyState(),
            minElapsedHours = 24,
            minSignalUnits = 5,
            scanThrottleMinutes = 10,
            activeTurn = false,
            lockHeld = false,
        )

        assertFalse(decision.due)
        assertEquals(listOf("disabled"), decision.reasons)
    }

    @Test fun `due when enough time and signal accumulated`() {
        val decision = DreamDueGate.evaluate(
            enabled = true,
            now = now,
            state = readyState(),
            minElapsedHours = 24,
            minSignalUnits = 5,
            scanThrottleMinutes = 10,
            activeTurn = false,
            lockHeld = false,
        )

        assertTrue(decision.due)
        assertEquals(emptyList<String>(), decision.reasons)
    }

    @Test fun `not due when active turn is running`() {
        val decision = DreamDueGate.evaluate(
            enabled = true,
            now = now,
            state = readyState(),
            minElapsedHours = 24,
            minSignalUnits = 5,
            scanThrottleMinutes = 10,
            activeTurn = true,
            lockHeld = false,
        )

        assertFalse(decision.due)
        assertTrue(decision.reasons.contains("active-turn"))
    }

    @Test fun `malformed timestamps do not block due when other gates pass`() {
        val decision = DreamDueGate.evaluate(
            enabled = true,
            now = now,
            state = DriftState(
                dreamLastSuccessfulScanAt = "not-a-timestamp",
                dreamLastDueCheckAt = "also-not-a-timestamp",
                dreamProcessedSignalUnits = 2,
                dreamObservedSignalUnits = 8,
            ),
            minElapsedHours = 24,
            minSignalUnits = 5,
            scanThrottleMinutes = 10,
            activeTurn = false,
            lockHeld = false,
        )

        assertTrue(decision.due)
        assertEquals(emptyList<String>(), decision.reasons)
    }

    @Test fun `zero thresholds can make due pass with no prior timestamps`() {
        val decision = DreamDueGate.evaluate(
            enabled = true,
            now = now,
            state = DriftState(),
            minElapsedHours = 0,
            minSignalUnits = 0,
            scanThrottleMinutes = 0,
            activeTurn = false,
            lockHeld = false,
        )

        assertTrue(decision.due)
        assertEquals(emptyList<String>(), decision.reasons)
    }

    private fun readyState(): DriftState = DriftState(
        dreamLastSuccessfulScanAt = "2026-05-08T11:00:00Z",
        dreamLastDueCheckAt = "2026-05-09T11:00:00Z",
        dreamProcessedSignalUnits = 3,
        dreamObservedSignalUnits = 9,
    )
}
