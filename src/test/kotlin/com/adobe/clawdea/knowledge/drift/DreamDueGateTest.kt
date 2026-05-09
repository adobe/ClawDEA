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

    @Test fun `not due when knowledge layer is disabled`() {
        val decision = evaluate(knowledgeLayerEnabled = false)

        assertFalse(decision.due)
        assertEquals(listOf("knowledge-layer-disabled"), decision.reasons)
    }

    @Test fun `not due when dream maintenance is disabled`() {
        val decision = evaluate(dreamMaintenanceEnabled = false)

        assertFalse(decision.due)
        assertEquals(listOf("dream-maintenance-disabled"), decision.reasons)
    }

    @Test fun `due when enough time and signal accumulated`() {
        val decision = evaluate()

        assertTrue(decision.due)
        assertEquals(emptyList<String>(), decision.reasons)
    }

    @Test fun `not due when active turn is running`() {
        val decision = evaluate(activeTurn = true)

        assertFalse(decision.due)
        assertTrue(decision.reasons.contains("active-turn"))
    }

    @Test fun `not due when lock is held`() {
        val decision = evaluate(lockHeld = true)

        assertFalse(decision.due)
        assertTrue(decision.reasons.contains("lock-held"))
    }

    @Test fun `not due when minimum elapsed time has not passed`() {
        val decision = evaluate(state = readyState().copy(dreamLastSuccessfulScanAt = "2026-05-09T00:00:00Z"))

        assertFalse(decision.due)
        assertTrue(decision.reasons.contains("elapsed-time"))
    }

    @Test fun `not due when accumulated signal is insufficient`() {
        val decision = evaluate(state = readyState().copy(dreamProcessedSignalUnits = 3, dreamObservedSignalUnits = 7))

        assertFalse(decision.due)
        assertTrue(decision.reasons.contains("insufficient-signal"))
    }

    @Test fun `not due when scan throttle has not elapsed`() {
        val decision = evaluate(state = readyState().copy(dreamLastDueCheckAt = "2026-05-09T11:55:00Z"))

        assertFalse(decision.due)
        assertTrue(decision.reasons.contains("scan-throttle"))
    }

    @Test fun `not due when recent failed run is inside scan throttle`() {
        val decision = evaluate(state = readyState().copy(
            dreamLastDueCheckAt = "2026-05-09T11:00:00Z",
            dreamLastFailedScanAt = "2026-05-09T11:55:00Z",
        ))

        assertFalse(decision.due)
        assertTrue(decision.reasons.contains("scan-throttle"))
    }

    @Test fun `old failed run does not block when due check is old`() {
        val decision = evaluate(state = readyState().copy(
            dreamLastDueCheckAt = "2026-05-09T11:00:00Z",
            dreamLastFailedScanAt = "2026-05-09T11:30:00Z",
        ))

        assertTrue(decision.due)
        assertEquals(emptyList<String>(), decision.reasons)
    }

    @Test fun `status alone does not trigger failed scan throttle`() {
        val decision = evaluate(state = readyState().copy(
            dreamLastDueCheckAt = "2026-05-09T11:00:00Z",
            dreamLastRunAt = "2026-05-09T11:55:00Z",
            dreamLastStatus = "timeout",
        ))

        assertTrue(decision.due)
        assertEquals(emptyList<String>(), decision.reasons)
    }

    @Test fun `malformed timestamps do not block due when other gates pass`() {
        val decision = evaluate(
            state = DriftState(
                dreamLastSuccessfulScanAt = "not-a-timestamp",
                dreamLastDueCheckAt = "also-not-a-timestamp",
                dreamProcessedSignalUnits = 2,
                dreamObservedSignalUnits = 8,
            ),
        )

        assertTrue(decision.due)
        assertEquals(emptyList<String>(), decision.reasons)
    }

    @Test fun `zero thresholds can make due pass with no prior timestamps`() {
        val decision = evaluate(
            state = DriftState(),
            minElapsedHours = 0,
            minSignalUnits = 0,
            scanThrottleMinutes = 0,
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

    private fun evaluate(
        knowledgeLayerEnabled: Boolean = true,
        dreamMaintenanceEnabled: Boolean = true,
        state: DriftState = readyState(),
        minElapsedHours: Int = 24,
        minSignalUnits: Int = 5,
        scanThrottleMinutes: Int = 10,
        activeTurn: Boolean = false,
        lockHeld: Boolean = false,
    ): DreamDueDecision = DreamDueGate.evaluate(
        knowledgeLayerEnabled = knowledgeLayerEnabled,
        dreamMaintenanceEnabled = dreamMaintenanceEnabled,
        now = now,
        state = state,
        minElapsedHours = minElapsedHours,
        minSignalUnits = minSignalUnits,
        scanThrottleMinutes = scanThrottleMinutes,
        activeTurn = activeTurn,
        lockHeld = lockHeld,
    )
}
