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
package com.adobe.clawdea.chat

import org.junit.Assert.*
import org.junit.Test

class FileDropHandlerTest {

    // ── Path resolution ──

    @Test
    fun `toRelativePath strips project base path`() {
        val result = FileDropHandler.toRelativePath(
            "/Users/me/project/src/Main.kt",
            "/Users/me/project",
        )
        assertEquals("src/Main.kt", result)
    }

    @Test
    fun `toRelativePath returns absolute path when file is outside project`() {
        val result = FileDropHandler.toRelativePath(
            "/tmp/external.txt",
            "/Users/me/project",
        )
        assertEquals("/tmp/external.txt", result)
    }

    @Test
    fun `toRelativePath handles trailing slash on base path`() {
        val result = FileDropHandler.toRelativePath(
            "/Users/me/project/src/Main.kt",
            "/Users/me/project/",
        )
        assertEquals("src/Main.kt", result)
    }

    // ── Reference formatting ──

    @Test
    fun `formatReferences produces single backtick-wrapped reference`() {
        val result = FileDropHandler.formatReferences(listOf("src/Main.kt"))
        assertEquals("@`src/Main.kt`", result)
    }

    @Test
    fun `formatReferences joins multiple paths with spaces`() {
        val result = FileDropHandler.formatReferences(listOf("a.kt", "b.kt", "c.kt"))
        assertEquals("@`a.kt` @`b.kt` @`c.kt`", result)
    }

    @Test
    fun `formatReferences returns empty string for empty list`() {
        val result = FileDropHandler.formatReferences(emptyList())
        assertEquals("", result)
    }

    // ── Smart spacing ──

    @Test
    fun `buildInsertText adds leading space when caret follows non-whitespace`() {
        val result = FileDropHandler.buildInsertText(
            existingText = "hello",
            caretPosition = 5,
            references = "@`file.kt`",
        )
        assertEquals(" @`file.kt` ", result)
    }

    @Test
    fun `buildInsertText no leading space at start of input`() {
        val result = FileDropHandler.buildInsertText(
            existingText = "",
            caretPosition = 0,
            references = "@`file.kt`",
        )
        assertEquals("@`file.kt` ", result)
    }

    @Test
    fun `buildInsertText no leading space after existing whitespace`() {
        val result = FileDropHandler.buildInsertText(
            existingText = "hello ",
            caretPosition = 6,
            references = "@`file.kt`",
        )
        assertEquals("@`file.kt` ", result)
    }

    @Test
    fun `buildInsertText no leading space after newline`() {
        val result = FileDropHandler.buildInsertText(
            existingText = "hello\n",
            caretPosition = 6,
            references = "@`file.kt`",
        )
        assertEquals("@`file.kt` ", result)
    }

    @Test
    fun `buildInsertText no trailing space when caret is before whitespace`() {
        val result = FileDropHandler.buildInsertText(
            existingText = "hello world",
            caretPosition = 5,
            references = "@`file.kt`",
        )
        assertEquals(" @`file.kt`", result)
    }
}
