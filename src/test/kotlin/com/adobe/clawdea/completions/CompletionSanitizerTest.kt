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
package com.adobe.clawdea.completions

import org.junit.Assert.*
import org.junit.Test

class CompletionSanitizerTest {

    @Test
    fun `strips leading markdown fence`() {
        val raw = "```kotlin\nval x = 1\n```"
        assertEquals("val x = 1", CompletionSanitizer.sanitize(raw, "    val "))
    }

    @Test
    fun `strips fence with no language tag`() {
        val raw = "```\nval x = 1\n```"
        assertEquals("val x = 1", CompletionSanitizer.sanitize(raw, "val "))
    }

    @Test
    fun `strips only leading fence if no closing fence`() {
        val raw = "```kotlin\nval x = 1"
        assertEquals("val x = 1", CompletionSanitizer.sanitize(raw, "val "))
    }

    @Test
    fun `passes through clean code unchanged`() {
        val raw = "val x = 1"
        assertEquals("val x = 1", CompletionSanitizer.sanitize(raw, "val "))
    }

    @Test
    fun `strips duplicate line comment prefix`() {
        val raw = "// This explains the code"
        assertEquals("This explains the code", CompletionSanitizer.sanitize(raw, "    // "))
    }

    @Test
    fun `strips duplicate line comment prefix without space`() {
        val raw = "//This explains the code"
        assertEquals("This explains the code", CompletionSanitizer.sanitize(raw, "    // "))
    }

    @Test
    fun `does not strip comment prefix when not on comment line`() {
        val raw = "// some comment"
        assertEquals("// some comment", CompletionSanitizer.sanitize(raw, "    val x = "))
    }

    @Test
    fun `strips duplicate block comment star`() {
        val raw = "* @param name the name"
        assertEquals("@param name the name", CompletionSanitizer.sanitize(raw, "     * "))
    }

    @Test
    fun `strips duplicate hash comment`() {
        val raw = "# This is a comment"
        assertEquals("This is a comment", CompletionSanitizer.sanitize(raw, "# "))
    }

    @Test
    fun `handles fence plus duplicate comment prefix`() {
        val raw = "```kotlin\n// This explains the code\n```"
        assertEquals("This explains the code", CompletionSanitizer.sanitize(raw, "    // "))
    }

    @Test
    fun `preserves multiline completions after dedup`() {
        val raw = "// line one\n// line two"
        assertEquals("line one\n// line two", CompletionSanitizer.sanitize(raw, "// "))
    }

    @Test
    fun `trims trailing whitespace`() {
        val raw = "val x = 1   \n   "
        assertEquals("val x = 1", CompletionSanitizer.sanitize(raw, "val "))
    }
}
