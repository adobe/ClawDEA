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
import org.junit.Test
import java.nio.file.Files

class DriftStateStoreTest {

    @Test fun `read returns empty state when file is missing`() {
        val tmp = Files.createTempDirectory("drift")
        val state = DriftStateStore.read(claudeDir = tmp)
        assertEquals(emptyList<String>(), state.dismissed)
    }

    @Test fun `write then read round-trips dismissed list`() {
        val tmp = Files.createTempDirectory("drift")
        DriftStateStore.write(claudeDir = tmp,
            state = DriftState(lastScanAt = "2026-05-04T10:00:00Z", dismissed = listOf("a", "b")))
        val state = DriftStateStore.read(claudeDir = tmp)
        assertEquals(listOf("a", "b"), state.dismissed)
        assertEquals("2026-05-04T10:00:00Z", state.lastScanAt)
    }

    @Test fun `write then read round-trips dream state fields`() {
        val tmp = Files.createTempDirectory("drift")
        DriftStateStore.write(claudeDir = tmp,
            state = DriftState(
                dreamLastRunAt = "2026-05-09T09:00:00Z",
                dreamLastSuccessfulScanAt = "2026-05-09T08:00:00Z",
                dreamLastFailedScanAt = "2026-05-09T08:30:00Z",
                dreamLastDueCheckAt = "2026-05-09T07:00:00Z",
                dreamLastStatus = "completed",
                dreamProcessedSignalUnits = 4,
                dreamObservedSignalUnits = 11,
                dreamFilteredCandidateCount = 2,
                dreamLockOwner = "dream-worker",
                dreamLockAcquiredAt = "2026-05-09T06:00:00Z",
            ))
        val state = DriftStateStore.read(claudeDir = tmp)
        assertEquals("2026-05-09T09:00:00Z", state.dreamLastRunAt)
        assertEquals("2026-05-09T08:00:00Z", state.dreamLastSuccessfulScanAt)
        assertEquals("2026-05-09T08:30:00Z", state.dreamLastFailedScanAt)
        assertEquals("2026-05-09T07:00:00Z", state.dreamLastDueCheckAt)
        assertEquals("completed", state.dreamLastStatus)
        assertEquals(4, state.dreamProcessedSignalUnits)
        assertEquals(11, state.dreamObservedSignalUnits)
        assertEquals(2, state.dreamFilteredCandidateCount)
        assertEquals("dream-worker", state.dreamLockOwner)
        assertEquals("2026-05-09T06:00:00Z", state.dreamLockAcquiredAt)
    }

    @Test fun `update modifies state atomically`() {
        val tmp = Files.createTempDirectory("drift")
        DriftStateStore.write(claudeDir = tmp,
            state = DriftState(lastScanAt = "x", dismissed = listOf("a")))
        DriftStateStore.update(claudeDir = tmp) { s -> s.copy(dismissed = s.dismissed + "b") }
        assertEquals(listOf("a", "b"), DriftStateStore.read(claudeDir = tmp).dismissed)
    }

    @Test fun `read tolerates malformed JSON by returning empty state`() {
        val tmp = Files.createTempDirectory("drift")
        val wikiDir = Files.createDirectories(tmp.resolve("wiki"))
        Files.writeString(wikiDir.resolve(".drift-state.json"), "{not json}")
        assertEquals(emptyList<String>(), DriftStateStore.read(claudeDir = tmp).dismissed)
    }

    @Test fun `probeMisses round-trips through JSON`() {
        val tmp = Files.createTempDirectory("drift")
        val misses = listOf(
            ProbeMiss("policy resolution", listOf("policies", "clientlibs"), 0, "abc123", "2026-05-12T10:00:00Z"),
            ProbeMiss("template mapping", listOf("page", "v2", "v3"), 1, "def456", "2026-05-12T11:00:00Z"),
        )
        DriftStateStore.write(claudeDir = tmp, state = DriftState(probeMisses = misses))
        val state = DriftStateStore.read(claudeDir = tmp)
        assertEquals(2, state.probeMisses.size)
        assertEquals("policy resolution", state.probeMisses[0].query)
        assertEquals(listOf("policies", "clientlibs"), state.probeMisses[0].pathTokens)
        assertEquals(0, state.probeMisses[0].hits)
        assertEquals("abc123", state.probeMisses[0].contextHash)
        assertEquals("2026-05-12T10:00:00Z", state.probeMisses[0].recordedAt)
    }

    @Test fun `probeMisses cap enforced at 200`() {
        val tmp = Files.createTempDirectory("drift")
        val misses = (1..250).map {
            ProbeMiss("query$it", listOf("token"), 0, "hash", "2026-05-12T10:00:00Z")
        }
        val capped = misses.takeLast(DriftState.MAX_PROBE_MISSES)
        DriftStateStore.write(claudeDir = tmp, state = DriftState(probeMisses = capped))
        val state = DriftStateStore.read(claudeDir = tmp)
        assertEquals(200, state.probeMisses.size)
        assertEquals("query51", state.probeMisses[0].query)
    }

    @Test fun `read defaults dream fields for old state JSON`() {
        val tmp = Files.createTempDirectory("drift")
        val wikiDir = Files.createDirectories(tmp.resolve("wiki"))
        Files.writeString(wikiDir.resolve(".drift-state.json"), """{"lastScanAt":"2026-05-04T10:00:00Z","dismissed":["a"]}""")
        val state = DriftStateStore.read(claudeDir = tmp)
        assertEquals("2026-05-04T10:00:00Z", state.lastScanAt)
        assertEquals(listOf("a"), state.dismissed)
        assertEquals("", state.dreamLastRunAt)
        assertEquals("", state.dreamLastSuccessfulScanAt)
        assertEquals("", state.dreamLastFailedScanAt)
        assertEquals("", state.dreamLastDueCheckAt)
        assertEquals("", state.dreamLastStatus)
        assertEquals(0, state.dreamProcessedSignalUnits)
        assertEquals(0, state.dreamObservedSignalUnits)
        assertEquals(0, state.dreamFilteredCandidateCount)
        assertEquals("", state.dreamLockOwner)
        assertEquals("", state.dreamLockAcquiredAt)
    }
}
