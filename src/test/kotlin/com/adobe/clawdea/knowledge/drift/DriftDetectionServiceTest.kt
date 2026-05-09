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

    @Test fun `collectRaw default path records cheap dream due check without invoking dreams`() {
        val tmp = Files.createTempDirectory("svc-dream-cheap")
        val now = Instant.parse("2026-05-09T12:34:56Z")
        var invoked = false

        val result = DriftDetectionService.collectRaw(
            projectRoot = tmp,
            claudeDir = tmp.resolve(".claude"),
            beforeState = DriftState(dreamObservedSignalUnits = 7),
            settingsState = ClawDEASettings.State().apply {
                enableKnowledgeLayer = true
                enableDreamWikiMaintenance = true
                dreamWikiMinElapsedHours = 0
                dreamWikiMinSignalUnits = 1
                dreamWikiScanThrottleMinutes = 0
            },
            now = now,
            detectDreams = { _, _, _, _, _, _ ->
                invoked = true
                DreamDetectionResult(emptyList(), "ok", 0, attempted = true, successful = true)
            },
        )

        assertFalse(invoked)
        assertEquals(emptyList<DriftEvent>(), result.events)
        assertEquals("2026-05-09T12:34:56Z", result.newState.dreamLastDueCheckAt)
        assertEquals("due", result.newState.dreamLastStatus)
        assertEquals("", result.newState.dreamLastRunAt)
        assertEquals("", result.newState.dreamLastSuccessfulScanAt)
        assertEquals("", result.newState.dreamLastFailedScanAt)
    }

    @Test fun `collectRaw default path reports not due when filesystem dream lock exists`() {
        val tmp = Files.createTempDirectory("svc-dream-cheap-lock")
        val claudeDir = tmp.resolve(".claude")
        val lockFile = claudeDir.resolve("wiki/.dream.lock")
        Files.createDirectories(lockFile.parent)
        Files.writeString(lockFile, "other-window\n2026-05-09T12:00:00Z")
        val now = Instant.parse("2026-05-09T12:34:56Z")
        var invoked = false

        val result = DriftDetectionService.collectRaw(
            projectRoot = tmp,
            claudeDir = claudeDir,
            beforeState = DriftState(dreamObservedSignalUnits = 7),
            settingsState = ClawDEASettings.State().apply {
                enableKnowledgeLayer = true
                enableDreamWikiMaintenance = true
                dreamWikiMinElapsedHours = 0
                dreamWikiMinSignalUnits = 1
                dreamWikiScanThrottleMinutes = 0
            },
            now = now,
            detectDreams = { _, _, _, _, _, _ ->
                invoked = true
                DreamDetectionResult(emptyList(), "ok", 0, attempted = true, successful = true)
            },
        )

        assertFalse(invoked)
        assertEquals(emptyList<DriftEvent>(), result.events)
        assertEquals("not-due:lock-held", result.newState.dreamLastStatus)
        assertEquals("2026-05-09T12:34:56Z", result.newState.dreamLastDueCheckAt)
        assertEquals("", result.newState.dreamLastRunAt)
        assertTrue(Files.exists(lockFile))
    }

    @Test fun `collectRaw default path increments cheap local dream signal units without invoking dreams`() {
        val tmp = Files.createTempDirectory("svc-dream-signals")
        val claudeDir = tmp.resolve(".claude")
        Files.createDirectories(claudeDir.resolve("wiki/concepts"))
        Files.writeString(claudeDir.resolve("wiki/index.md"), "# Wiki")
        Files.writeString(claudeDir.resolve("wiki/concepts/drift.md"), "# Drift")
        Files.writeString(claudeDir.resolve("REPO_STATE.md"), "repo state")
        Files.createDirectories(claudeDir.resolve("notes"))
        Files.writeString(claudeDir.resolve("notes/CURRENT.md"), "note")
        val now = Instant.parse("2026-05-09T12:34:56Z")
        var invoked = false

        val result = DriftDetectionService.collectRaw(
            projectRoot = tmp,
            claudeDir = claudeDir,
            beforeState = DriftState(
                dreamLastSuccessfulScanAt = "2026-05-08T12:34:56Z",
                dreamObservedSignalUnits = 0,
                dreamProcessedSignalUnits = 0,
            ),
            settingsState = ClawDEASettings.State().apply {
                enableKnowledgeLayer = true
                enableDreamWikiMaintenance = true
                dreamWikiMinElapsedHours = 0
                dreamWikiMinSignalUnits = 1
                dreamWikiScanThrottleMinutes = 0
            },
            now = now,
            detectDreams = { _, _, _, _, _, _ ->
                invoked = true
                DreamDetectionResult(emptyList(), "ok", 0, attempted = true, successful = true)
            },
        )

        assertFalse(invoked)
        assertTrue(result.newState.dreamObservedSignalUnits > 0)
        assertEquals("due", result.newState.dreamLastStatus)
    }

    @Test fun `collectRaw explicit dream scan appends dream events and persists success status`() {
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
        var invoked = false
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
            runDreamScan = true,
            detectDreams = { _, _, settings, _, force, activeTurn ->
                invoked = true
                capturedSettings = settings
                capturedForce = force
                capturedActiveTurn = activeTurn
                DreamDetectionResult(events = listOf(dreamEvent), status = "ok", filteredCandidateCount = 1, attempted = true, successful = true)
            },
        )

        assertTrue(invoked)
        assertTrue(result.events.last() is DriftEvent.DreamMissingConcept)
        assertEquals(dreamEvent, result.events.last())
        assertEquals(DreamWikiSettings(enabled = true, minElapsedHours = 12, minSignalUnits = 3, scanThrottleMinutes = 4), capturedSettings)
        assertEquals(true, capturedForce)
        assertEquals(false, capturedActiveTurn)
        assertEquals("already-dismissed", result.newState.dismissed.single())
        assertEquals("2026-05-09T12:34:56Z", result.newState.dreamLastRunAt)
        assertEquals("2026-05-09T12:34:56Z", result.newState.dreamLastDueCheckAt)
        assertEquals("ok", result.newState.dreamLastStatus)
        assertEquals("2026-05-09T12:34:56Z", result.newState.dreamLastSuccessfulScanAt)
        assertEquals("", result.newState.dreamLastFailedScanAt)
        assertEquals(7, result.newState.dreamProcessedSignalUnits)
        assertEquals(1, result.newState.dreamFilteredCandidateCount)
    }

    @Test fun `collectRaw explicit dream scan holds filesystem lock while running and releases it`() {
        val tmp = Files.createTempDirectory("svc-dream-lock")
        val claudeDir = tmp.resolve(".claude")
        val lockFile = claudeDir.resolve("wiki/.dream.lock")
        val now = Instant.parse("2026-05-09T12:34:56Z")
        var lockObservedDuringScan = false

        val result = DriftDetectionService.collectRaw(
            projectRoot = tmp,
            claudeDir = claudeDir,
            beforeState = DriftState(dreamObservedSignalUnits = 3),
            settingsState = ClawDEASettings.State().apply {
                enableKnowledgeLayer = true
                enableDreamWikiMaintenance = true
            },
            now = now,
            runDreamScan = true,
            detectDreams = { _, _, _, _, _, _ ->
                lockObservedDuringScan = Files.exists(lockFile)
                DreamDetectionResult(emptyList(), "ok", 0, attempted = true, successful = true)
            },
        )

        assertTrue(lockObservedDuringScan)
        assertFalse(Files.exists(lockFile))
        assertEquals("ok", result.newState.dreamLastStatus)
    }

    @Test fun `collectRaw explicit dream scan skips invocation when filesystem lock already exists`() {
        val tmp = Files.createTempDirectory("svc-dream-lock-held")
        val claudeDir = tmp.resolve(".claude")
        val lockFile = claudeDir.resolve("wiki/.dream.lock")
        Files.createDirectories(lockFile.parent)
        Files.writeString(lockFile, "other-window\n2026-05-09T12:00:00Z")
        val now = Instant.parse("2026-05-09T12:34:56Z")
        var invoked = false

        val result = DriftDetectionService.collectRaw(
            projectRoot = tmp,
            claudeDir = claudeDir,
            beforeState = DriftState(dreamObservedSignalUnits = 3),
            settingsState = ClawDEASettings.State().apply {
                enableKnowledgeLayer = true
                enableDreamWikiMaintenance = true
            },
            now = now,
            runDreamScan = true,
            detectDreams = { _, _, _, _, _, _ ->
                invoked = true
                DreamDetectionResult(emptyList(), "ok", 0, attempted = true, successful = true)
            },
        )

        assertFalse(invoked)
        assertTrue(Files.exists(lockFile))
        assertEquals("not-run:lock-held", result.newState.dreamLastStatus)
    }

    @Test fun `collectRaw explicit dream scan respects held dream lock`() {
        val tmp = Files.createTempDirectory("svc-dream-locked")
        val now = Instant.parse("2026-05-09T12:34:56Z")
        var invoked = false

        val result = DriftDetectionService.collectRaw(
            projectRoot = tmp,
            claudeDir = tmp.resolve(".claude"),
            beforeState = DriftState(
                dreamLockOwner = "other-window",
                dreamProcessedSignalUnits = 2,
                dreamObservedSignalUnits = 7,
            ),
            settingsState = ClawDEASettings.State(),
            now = now,
            runDreamScan = true,
            detectDreams = { _, _, _, _, _, _ ->
                invoked = true
                DreamDetectionResult(emptyList(), "ok", 0, attempted = true, successful = true)
            },
        )

        assertFalse(invoked)
        assertEquals(emptyList<DriftEvent>(), result.events)
        assertEquals("2026-05-09T12:34:56Z", result.newState.dreamLastDueCheckAt)
        assertEquals("not-run:lock-held", result.newState.dreamLastStatus)
        assertEquals("", result.newState.dreamLastRunAt)
        assertEquals("", result.newState.dreamLastSuccessfulScanAt)
        assertEquals("", result.newState.dreamLastFailedScanAt)
        assertEquals(2, result.newState.dreamProcessedSignalUnits)
    }

    @Test fun `collectRaw explicit dream scan respects disabled dream maintenance setting`() {
        val tmp = Files.createTempDirectory("svc-dream-disabled")
        val now = Instant.parse("2026-05-09T12:34:56Z")
        var invoked = false

        val result = DriftDetectionService.collectRaw(
            projectRoot = tmp,
            claudeDir = tmp.resolve(".claude"),
            beforeState = DriftState(
                dreamProcessedSignalUnits = 2,
                dreamObservedSignalUnits = 7,
            ),
            settingsState = ClawDEASettings.State().apply {
                enableKnowledgeLayer = true
                enableDreamWikiMaintenance = false
            },
            now = now,
            runDreamScan = true,
            detectDreams = { _, _, _, _, _, _ ->
                invoked = true
                DreamDetectionResult(emptyList(), "ok", 0, attempted = true, successful = true)
            },
        )

        assertFalse(invoked)
        assertEquals(emptyList<DriftEvent>(), result.events)
        assertEquals("2026-05-09T12:34:56Z", result.newState.dreamLastDueCheckAt)
        assertEquals("not-run:disabled", result.newState.dreamLastStatus)
        assertEquals("", result.newState.dreamLastRunAt)
        assertEquals("", result.newState.dreamLastSuccessfulScanAt)
        assertEquals("", result.newState.dreamLastFailedScanAt)
        assertEquals(2, result.newState.dreamProcessedSignalUnits)
    }

    @Test fun `collectRaw explicit dream scan persists failed status timestamp`() {
        val tmp = Files.createTempDirectory("svc-dream-failed")
        val now = Instant.parse("2026-05-09T12:34:56Z")

        val result = DriftDetectionService.collectRaw(
            projectRoot = tmp,
            claudeDir = tmp.resolve(".claude"),
            beforeState = DriftState(
                dismissed = listOf("already-dismissed"),
                dreamProcessedSignalUnits = 2,
                dreamObservedSignalUnits = 7,
            ),
            settingsState = ClawDEASettings.State(),
            now = now,
            runDreamScan = true,
            detectDreams = { _, _, _, _, _, _ ->
                DreamDetectionResult(emptyList(), "invalid:Malformed JSON", 0, attempted = true, successful = false)
            },
        )

        assertEquals("already-dismissed", result.newState.dismissed.single())
        assertEquals("2026-05-09T12:34:56Z", result.newState.dreamLastRunAt)
        assertEquals("2026-05-09T12:34:56Z", result.newState.dreamLastDueCheckAt)
        assertEquals("invalid:Malformed JSON", result.newState.dreamLastStatus)
        assertEquals("", result.newState.dreamLastSuccessfulScanAt)
        assertEquals("2026-05-09T12:34:56Z", result.newState.dreamLastFailedScanAt)
        assertEquals(2, result.newState.dreamProcessedSignalUnits)
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
