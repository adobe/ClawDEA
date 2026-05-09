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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefParserTest {

    @Test
    fun `bare path parses with no line info`() {
        val p = RefParser.parse("ChatPanel.kt")!!
        assertEquals("ChatPanel.kt", p.path)
        assertNull(p.startLine)
        assertNull(p.endLine)
        assertEquals(0, p.column)
        assertFalse(p.isRange)
    }

    @Test
    fun `path with single line is 0-based`() {
        val p = RefParser.parse("ChatPanel.kt:84")!!
        assertEquals("ChatPanel.kt", p.path)
        assertEquals(83, p.startLine)
        assertNull(p.endLine)
        assertFalse(p.isRange)
    }

    @Test
    fun `path with line and column`() {
        val p = RefParser.parse("ChatPanel.kt:84:5")!!
        assertEquals("ChatPanel.kt", p.path)
        assertEquals(83, p.startLine)
        assertEquals(4, p.column)
        assertNull(p.endLine)
        assertFalse(p.isRange)
    }

    @Test
    fun `path with line range parses both endpoints`() {
        val p = RefParser.parse("ChatPanel.kt:84-120")!!
        assertEquals("ChatPanel.kt", p.path)
        assertEquals(83, p.startLine)
        assertEquals(119, p.endLine)
        assertEquals(0, p.column)
        assertTrue(p.isRange)
    }

    @Test
    fun `single-line range is allowed`() {
        val p = RefParser.parse("Foo.kt:10-10")!!
        assertEquals(9, p.startLine)
        assertEquals(9, p.endLine)
        assertTrue(p.isRange)
    }

    @Test
    fun `parens in query are stripped before parsing`() {
        val p = RefParser.parse("BookService.listAll()")!!
        assertEquals("BookService.listAll", p.path)
        assertNull(p.startLine)
    }

    @Test
    fun `fully qualified name with line`() {
        val p = RefParser.parse("com.adobe.clawdea.chat.ChatPanel:42")!!
        assertEquals("com.adobe.clawdea.chat.ChatPanel", p.path)
        assertEquals(41, p.startLine)
        assertNull(p.endLine)
    }

    @Test
    fun `fully qualified name with range`() {
        val p = RefParser.parse("com.adobe.clawdea.chat.ChatPanel:42-50")!!
        assertEquals("com.adobe.clawdea.chat.ChatPanel", p.path)
        assertEquals(41, p.startLine)
        assertEquals(49, p.endLine)
        assertTrue(p.isRange)
    }
}
