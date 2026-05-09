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
package com.adobe.clawdea.knowledge.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.Month

class NoteContentBuilderTest {
    private val sampleTime: LocalDateTime = LocalDateTime.of(2026, Month.MAY, 4, 9, 30)

    @Test fun `prepends a timestamped heading to existing content`() {
        val out = NoteContentBuilder.prepend(
            existingContent = "## 2026-05-03 18:00\n\nyesterday's note\n",
            body = "today's note",
            now = sampleTime,
        )
        assertEquals(
            "## 2026-05-04 09:30\n\ntoday's note\n\n## 2026-05-03 18:00\n\nyesterday's note\n",
            out,
        )
    }

    @Test fun `treats null existing content as empty`() {
        val out = NoteContentBuilder.prepend(
            existingContent = null,
            body = "first note",
            now = sampleTime,
        )
        assertEquals("## 2026-05-04 09:30\n\nfirst note\n\n", out)
    }

    @Test fun `treats blank existing content as empty`() {
        val out = NoteContentBuilder.prepend(existingContent = "   ", body = "first", now = sampleTime)
        assertTrue(out.startsWith("## 2026-05-04 09:30"))
        assertTrue(out.contains("first"))
    }

    @Test fun `trims trailing whitespace from the body but preserves internal newlines`() {
        val out = NoteContentBuilder.prepend(
            existingContent = null,
            body = "line one\nline two\n\n",
            now = sampleTime,
        )
        assertEquals("## 2026-05-04 09:30\n\nline one\nline two\n\n", out)
    }

    @Test fun `formats single-digit fields with zero padding`() {
        val out = NoteContentBuilder.prepend(
            existingContent = null,
            body = "x",
            now = LocalDateTime.of(2026, Month.JANUARY, 7, 5, 9),
        )
        assertTrue("expected zero-padded date/time, got: $out", out.startsWith("## 2026-01-07 05:09"))
    }
}
