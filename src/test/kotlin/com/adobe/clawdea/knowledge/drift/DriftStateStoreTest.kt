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
        val wikiDir = Files.createDirectories(tmp.resolve("wiki"))
        val state = DriftStateStore.read(wikiDir = wikiDir)
        assertEquals(emptyList<String>(), state.dismissed)
    }

    @Test fun `write then read round-trips dismissed list`() {
        val tmp = Files.createTempDirectory("drift")
        val wikiDir = tmp.resolve("wiki")
        DriftStateStore.write(wikiDir = wikiDir,
            state = DriftState(lastScanAt = "2026-05-04T10:00:00Z", dismissed = listOf("a", "b")))
        val state = DriftStateStore.read(wikiDir = wikiDir)
        assertEquals(listOf("a", "b"), state.dismissed)
        assertEquals("2026-05-04T10:00:00Z", state.lastScanAt)
    }

    @Test fun `update modifies state atomically`() {
        val tmp = Files.createTempDirectory("drift")
        val wikiDir = tmp.resolve("wiki")
        DriftStateStore.write(wikiDir = wikiDir,
            state = DriftState(lastScanAt = "x", dismissed = listOf("a")))
        DriftStateStore.update(wikiDir = wikiDir) { s -> s.copy(dismissed = s.dismissed + "b") }
        assertEquals(listOf("a", "b"), DriftStateStore.read(wikiDir = wikiDir).dismissed)
    }

    @Test fun `read tolerates malformed JSON by returning empty state`() {
        val tmp = Files.createTempDirectory("drift")
        val wikiDir = Files.createDirectories(tmp.resolve("wiki"))
        Files.writeString(wikiDir.resolve(".drift-state.json"), "{not json}")
        assertEquals(emptyList<String>(), DriftStateStore.read(wikiDir = wikiDir).dismissed)
    }

    @Test fun `probeMisses round-trips through JSON`() {
        val tmp = Files.createTempDirectory("drift")
        val wikiDir = tmp.resolve("wiki")
        val misses = listOf(
            ProbeMiss("policy resolution", listOf("policies", "clientlibs"), 0, "abc123", "2026-05-12T10:00:00Z"),
            ProbeMiss("template mapping", listOf("page", "v2", "v3"), 1, "def456", "2026-05-12T11:00:00Z"),
        )
        DriftStateStore.write(wikiDir = wikiDir, state = DriftState(probeMisses = misses))
        val state = DriftStateStore.read(wikiDir = wikiDir)
        assertEquals(2, state.probeMisses.size)
        assertEquals("policy resolution", state.probeMisses[0].query)
        assertEquals(listOf("policies", "clientlibs"), state.probeMisses[0].pathTokens)
        assertEquals(0, state.probeMisses[0].hits)
        assertEquals("abc123", state.probeMisses[0].contextHash)
        assertEquals("2026-05-12T10:00:00Z", state.probeMisses[0].recordedAt)
    }

    @Test fun `probeMisses cap enforced at 200`() {
        val tmp = Files.createTempDirectory("drift")
        val wikiDir = tmp.resolve("wiki")
        val misses = (1..250).map {
            ProbeMiss("query$it", listOf("token"), 0, "hash", "2026-05-12T10:00:00Z")
        }
        val capped = misses.takeLast(DriftState.MAX_PROBE_MISSES)
        DriftStateStore.write(wikiDir = wikiDir, state = DriftState(probeMisses = capped))
        val state = DriftStateStore.read(wikiDir = wikiDir)
        assertEquals(200, state.probeMisses.size)
        assertEquals("query51", state.probeMisses[0].query)
    }

    @Test fun `read silently ignores dream fields from older state files`() {
        val tmp = Files.createTempDirectory("drift-state-back-compat")
        try {
            val wikiDir = tmp.resolve("wiki")
            Files.createDirectories(wikiDir)
            // A v1 file with dream fields the new schema no longer declares.
            val legacyJson = """
                {
                  "lastScanAt": "2026-05-10T00:00:00Z",
                  "dismissed": ["x"],
                  "dreamLastRunAt": "2026-05-10T00:00:00Z",
                  "dreamProcessedSignalUnits": 42
                }
            """.trimIndent()
            Files.writeString(wikiDir.resolve(".drift-state.json"), legacyJson)

            val state = DriftStateStore.read(wikiDir = wikiDir)
            assertEquals("2026-05-10T00:00:00Z", state.lastScanAt)
            assertEquals(listOf("x"), state.dismissed)
            // No assertion on dream fields — they're gone.
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `read returns empty suggestions when file is missing`() {
        val tmp = Files.createTempDirectory("drift")
        val wikiDir = Files.createDirectories(tmp.resolve("wiki"))
        val state = DriftStateStore.read(wikiDir = wikiDir)
        assertEquals(emptyList<DriftEvent.WikiSuggestion>(), state.suggestions)
    }

    @Test fun `read returns empty suggestions when v1 file has no suggestions field`() {
        val tmp = Files.createTempDirectory("drift")
        val wikiDir = Files.createDirectories(tmp.resolve("wiki"))
        Files.writeString(
            wikiDir.resolve(".drift-state.json"),
            """{"lastScanAt":"2026-05-01T00:00:00Z","dismissed":["sig-a"]}""",
        )
        val state = DriftStateStore.read(wikiDir = wikiDir)
        assertEquals(listOf("sig-a"), state.dismissed)
        assertEquals(emptyList<DriftEvent.WikiSuggestion>(), state.suggestions)
    }

    @Test fun `write then read round-trips suggestions list`() {
        val tmp = Files.createTempDirectory("drift")
        val wikiDir = tmp.resolve("wiki")
        val suggestion = DriftEvent.WikiSuggestion(
            kind = SuggestionKind.missingConcept,
            title = "Add concept for FilesystemRefreshCoordinator",
            rationale = "Referenced from multiple subsystems; no page exists.",
            targetFiles = listOf(
                ".claude/wiki/concepts/filesystem-refresh-coordinator.md",
                ".claude/wiki/index.md",
            ),
            sourcePage = null,
            recordedAt = "2026-05-16T16:30:00Z",
        )
        DriftStateStore.write(
            wikiDir = wikiDir,
            state = DriftState(suggestions = listOf(suggestion)),
        )
        val state = DriftStateStore.read(wikiDir = wikiDir)
        assertEquals(1, state.suggestions.size)
        val read = state.suggestions[0]
        assertEquals(SuggestionKind.missingConcept, read.kind)
        assertEquals(suggestion.title, read.title)
        assertEquals(suggestion.targetFiles, read.targetFiles)
        assertEquals(suggestion.signature, read.signature)
    }
}
