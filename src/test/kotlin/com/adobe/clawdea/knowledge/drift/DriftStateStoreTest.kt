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
                dreamLastDueCheckAt = "2026-05-09T07:00:00Z",
                dreamLastStatus = "completed",
                dreamProcessedSignalUnits = 4,
                dreamObservedSignalUnits = 11,
                dreamFilteredCandidateCount = 2,
            ))
        val state = DriftStateStore.read(claudeDir = tmp)
        assertEquals("2026-05-09T09:00:00Z", state.dreamLastRunAt)
        assertEquals("2026-05-09T08:00:00Z", state.dreamLastSuccessfulScanAt)
        assertEquals("2026-05-09T07:00:00Z", state.dreamLastDueCheckAt)
        assertEquals("completed", state.dreamLastStatus)
        assertEquals(4, state.dreamProcessedSignalUnits)
        assertEquals(11, state.dreamObservedSignalUnits)
        assertEquals(2, state.dreamFilteredCandidateCount)
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

    @Test fun `read defaults dream fields for old state JSON`() {
        val tmp = Files.createTempDirectory("drift")
        val wikiDir = Files.createDirectories(tmp.resolve("wiki"))
        Files.writeString(wikiDir.resolve(".drift-state.json"), """{"lastScanAt":"2026-05-04T10:00:00Z","dismissed":["a"]}""")
        val state = DriftStateStore.read(claudeDir = tmp)
        assertEquals("2026-05-04T10:00:00Z", state.lastScanAt)
        assertEquals(listOf("a"), state.dismissed)
        assertEquals("", state.dreamLastRunAt)
        assertEquals("", state.dreamLastSuccessfulScanAt)
        assertEquals("", state.dreamLastDueCheckAt)
        assertEquals("", state.dreamLastStatus)
        assertEquals(0, state.dreamProcessedSignalUnits)
        assertEquals(0, state.dreamObservedSignalUnits)
        assertEquals(0, state.dreamFilteredCandidateCount)
    }
}
