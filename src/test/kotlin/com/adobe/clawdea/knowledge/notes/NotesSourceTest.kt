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

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesSourceTest {
    @Test fun `returns null when CURRENT_md does not exist`() {
        val result = NotesSource.renderBody(currentMdContent = null)
        assertNull(result)
    }

    @Test fun `wraps content with the directive when CURRENT_md exists`() {
        val result = NotesSource.renderBody(currentMdContent = "## 2026-05-04 09:00\n\nremember the milk\n")!!
        assertTrue(result.contains("Personal notes"))
        assertTrue(result.contains("remember the milk"))
    }

    @Test fun `truncates the tail when content exceeds 16 KB and appends marker`() {
        val big = "x".repeat(20_000)
        val result = NotesSource.renderBody(currentMdContent = big)!!
        assertTrue("body should contain the truncation marker", result.contains("...(older entries truncated)..."))
        assertTrue("body should preserve the head of the source", result.contains("xxxxxxxxxx"))
        assertTrue("body too large: ${result.length}", result.length < 17_000)
    }

    @Test fun `does not truncate when content fits under 16 KB`() {
        val small = "## 2026-05-04 09:00\n\nshort note\n"
        val result = NotesSource.renderBody(currentMdContent = small)!!
        assertTrue(!result.contains("...(older entries truncated)..."))
        assertTrue(result.contains("short note"))
    }

    @Test fun `includes the directive line verbatim`() {
        val result = NotesSource.renderBody(currentMdContent = "anything")!!
        assertTrue(result.contains("Personal notes from this user"))
    }

    @Test fun `directive precedes the content`() {
        val result = NotesSource.renderBody(currentMdContent = "MARKER_FOR_BODY")!!
        val directiveIdx = result.indexOf("Personal notes from this user")
        val markerIdx = result.indexOf("MARKER_FOR_BODY")
        assertTrue(directiveIdx >= 0 && markerIdx > directiveIdx)
    }
}
