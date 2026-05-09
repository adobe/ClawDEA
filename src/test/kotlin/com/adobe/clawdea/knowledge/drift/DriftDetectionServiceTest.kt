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

import com.adobe.clawdea.chat.DriftBanner
import com.adobe.clawdea.settings.ClawDEASettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class DriftDetectionServiceTest {

    @Test fun `dismissed events are filtered out`() {
        val raw = listOf<DriftEvent>(
            DriftEvent.CodeRename(Path.of("a.md"), "old/X.kt", null),
            DriftEvent.CodeRename(Path.of("b.md"), "old/Y.kt", null),
        )
        val state = DriftState(dismissed = listOf(raw[0].signature))
        val filtered = DriftDetectionService.filterDismissed(raw, state)
        assertEquals(1, filtered.size)
        assertEquals(raw[1], filtered.single())
    }

    @Test fun `auto-apply when enabled removes applied events from result and dismisses them`() {
        val tmp = Files.createTempDirectory("svc")
        val page = tmp.resolve("page.md")
        Files.writeString(page, "[Foo](old/Foo.kt)")
        val rawEvents = listOf<DriftEvent>(
            DriftEvent.CodeRename(page, "old/Foo.kt", "new/Foo.kt"),
        )
        val (remaining, applied) = DriftDetectionService.applyAndDismiss(
            events = rawEvents,
            autoUpdateEnabled = true,
            beforeState = DriftState(),
            today = "2026-05-04",
        )
        assertEquals(emptyList<DriftEvent>(), remaining)
        assertEquals(rawEvents, applied.events)
        assertTrue(applied.newState.dismissed.contains(rawEvents.single().signature))
    }

    @Test fun `auto-apply disabled returns all events unchanged`() {
        val rawEvents = listOf<DriftEvent>(
            DriftEvent.CodeRename(Path.of("a.md"), "old/X.kt", "new/X.kt"),
        )
        val (remaining, applied) = DriftDetectionService.applyAndDismiss(
            events = rawEvents,
            autoUpdateEnabled = false,
            beforeState = DriftState(),
            today = "2026-05-04",
        )
        assertEquals(rawEvents, remaining)
        assertEquals(emptyList<DriftEvent>(), applied.events)
    }

    @Test fun `collectRaw appends dream events and persists dream success status`() {
        val tmp = Files.createTempDirectory("svc-dream")
        val claudeDir = tmp.resolve(".claude")
        val dreamEvent = DriftEvent.DreamMissingConcept(
            targetFile = tmp.resolve(".claude/wiki/concepts/dream.md"),
            title = "Add Dream concept",
            patchPlan = "Create a new concept page.",
            signatureKey = "dream-concept",
        )
        val beforeState = DriftState(
            dismissed = listOf("already-dismissed"),
            dreamProcessedSignalUnits = 2,
            dreamObservedSignalUnits = 7,
        )
        var capturedSettings: DreamWikiSettings? = null
        var capturedForce: Boolean? = null
        var capturedActiveTurn: Boolean? = null
        val now = Instant.parse("2026-05-09T12:34:56Z")

        val result = DriftDetectionService.collectRaw(
            projectRoot = tmp,
            claudeDir = claudeDir,
            beforeState = beforeState,
            settingsState = ClawDEASettings.State().apply {
                enableKnowledgeLayer = true
                enableDreamWikiMaintenance = true
                dreamWikiMinElapsedHours = 12
                dreamWikiMinSignalUnits = 3
                dreamWikiScanThrottleMinutes = 4
            },
            now = now,
            detectDreams = { _, _, settings, _, force, activeTurn ->
                capturedSettings = settings
                capturedForce = force
                capturedActiveTurn = activeTurn
                DreamDetectionResult(events = listOf(dreamEvent), status = "ok", filteredCandidateCount = 1)
            },
        )

        assertTrue(result.events.last() is DriftEvent.DreamMissingConcept)
        assertEquals(dreamEvent, result.events.last())
        assertEquals(DreamWikiSettings(enabled = true, minElapsedHours = 12, minSignalUnits = 3, scanThrottleMinutes = 4), capturedSettings)
        assertEquals(false, capturedForce)
        assertEquals(false, capturedActiveTurn)
        assertEquals("already-dismissed", result.newState.dismissed.single())
        assertEquals("2026-05-09T12:34:56Z", result.newState.dreamLastDueCheckAt)
        assertEquals("ok", result.newState.dreamLastStatus)
        assertEquals("2026-05-09T12:34:56Z", result.newState.dreamLastSuccessfulScanAt)
        assertEquals(7, result.newState.dreamProcessedSignalUnits)
        assertEquals(1, result.newState.dreamFilteredCandidateCount)
    }

    @Test fun `auto-apply leaves review-only DreamMissingConcept pending`() {
        val event = DriftEvent.DreamMissingConcept(
            targetFile = Path.of(".claude/wiki/concepts/dream.md"),
            title = "Add Dream concept",
            patchPlan = "Create a new concept page.",
            signatureKey = "dream-concept",
        )
        val (remaining, applied) = DriftDetectionService.applyAndDismiss(
            events = listOf(event),
            autoUpdateEnabled = true,
            beforeState = DriftState(),
            today = "2026-05-09",
        )

        assertEquals(listOf(event), remaining)
        assertEquals(emptyList<DriftEvent>(), applied.events)
        assertFalse(applied.newState.dismissed.contains(event.signature))
    }

    @Test fun `banner labels pending dream events as maintenance suggestions`() {
        var html = ""
        val banner = DriftBanner(
            updateHtml = { html = it },
            onInsertCommand = {},
            onDismissAll = {},
        )

        banner.setEvents(
            listOf(
                DriftEvent.DreamMissingConcept(
                    targetFile = Path.of(".claude/wiki/concepts/dream.md"),
                    title = "Add Dream concept",
                    patchPlan = "Create a new concept page.",
                    signatureKey = "dream-concept",
                ),
            ),
        )

        assertTrue(html.contains("wiki has 1 maintenance suggestion"))
        assertFalse(html.contains("stale ref"))
    }
}
