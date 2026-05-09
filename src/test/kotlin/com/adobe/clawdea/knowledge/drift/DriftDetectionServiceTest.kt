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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

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
}
