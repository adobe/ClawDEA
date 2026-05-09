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
package com.adobe.clawdea.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSearchTextToolTest {

    @Test
    fun `scanText emits matches with 1-based line numbers`() {
        val text = "first line\nsecond enable-auto-mode line\nthird line\n"
        val out = mutableListOf<String>()
        McpSearchTextTool.scanText(
            text = text,
            pattern = Regex(Regex.escape("enable-auto-mode")),
            relPath = "src/Foo.kt",
            out = out,
        )
        assertEquals(1, out.size)
        assertTrue(out[0].startsWith("--- src/Foo.kt:2 ---\n"))
        assertTrue(out[0].contains("second enable-auto-mode line"))
    }

    @Test
    fun `scanText reports multiple matches across lines`() {
        val text = "alpha foo\nbeta\nfoo gamma\nfoo delta\n"
        val out = mutableListOf<String>()
        McpSearchTextTool.scanText(
            text = text,
            pattern = Regex("foo"),
            relPath = "x.txt",
            out = out,
        )
        assertEquals(3, out.size)
        assertTrue(out[0].contains(":1"))
        assertTrue(out[1].contains(":3"))
        assertTrue(out[2].contains(":4"))
    }

    @Test
    fun `scanText stops at MAX_MATCHES`() {
        val text = (1..100).joinToString("\n") { "match $it" }
        val out = mutableListOf<String>()
        McpSearchTextTool.scanText(
            text = text,
            pattern = Regex("match"),
            relPath = "f",
            out = out,
        )
        assertEquals(McpSearchTextTool.MAX_MATCHES, out.size)
    }

    @Test
    fun `scanText handles last line with no trailing newline`() {
        val text = "alpha\nbeta needle"
        val out = mutableListOf<String>()
        McpSearchTextTool.scanText(
            text = text,
            pattern = Regex("needle"),
            relPath = "f",
            out = out,
        )
        assertEquals(1, out.size)
        assertTrue(out[0].contains(":2"))
    }

    @Test
    fun `scanText truncates very long lines`() {
        val longLine = "x".repeat(500) + " hit"
        val text = "$longLine\n"
        val out = mutableListOf<String>()
        McpSearchTextTool.scanText(
            text = text,
            pattern = Regex("hit"),
            relPath = "f",
            out = out,
        )
        assertEquals(1, out.size)
        // Truncated line must end with the ellipsis marker
        assertTrue(out[0].trimEnd().endsWith("…"))
    }

    @Test
    fun `compileGlob matches simple star pattern`() {
        val r = McpSearchTextTool.compileGlob("*.kt")
        assertTrue(r.matches("Foo.kt"))
        assertTrue(r.matches("nested.with.dots.kt"))
        assertFalse(r.matches("Foo.java"))
        assertFalse(r.matches("kt"))
    }

    @Test
    fun `compileGlob escapes regex metacharacters in literal segments`() {
        val r = McpSearchTextTool.compileGlob("a+b.kt")
        assertTrue(r.matches("a+b.kt"))
        assertFalse(r.matches("ab.kt")) // '+' is literal, not a quantifier
    }

    @Test
    fun `compileGlob is case insensitive`() {
        val r = McpSearchTextTool.compileGlob("*.KT")
        assertTrue(r.matches("Foo.kt"))
    }

    @Test
    fun `compileGlob question mark matches single char`() {
        val r = McpSearchTextTool.compileGlob("F?o.kt")
        assertTrue(r.matches("Foo.kt"))
        assertTrue(r.matches("Fxo.kt"))
        assertFalse(r.matches("Foooo.kt"))
    }

    @Test
    fun `relativize strips project base path`() {
        assertEquals(
            "src/Foo.kt",
            McpSearchTextTool.relativize("/proj/src/Foo.kt", "/proj"),
        )
    }

    @Test
    fun `relativize returns full path when outside base`() {
        assertEquals(
            "/other/path",
            McpSearchTextTool.relativize("/other/path", "/proj"),
        )
    }
}
