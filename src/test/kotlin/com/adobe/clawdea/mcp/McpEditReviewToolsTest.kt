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

import org.junit.Assert.*
import org.junit.Test

class McpEditReviewToolsTest {

    @Test
    fun `buildProposedEdit applies replacement`() {
        val original = "line1\nline2\nline3"
        val result = McpEditReviewTools.buildProposedEdit(original, "line2", "replaced")
        assertEquals("line1\nreplaced\nline3", result)
    }

    @Test
    fun `buildProposedEdit returns original when old_string not found`() {
        val original = "line1\nline2"
        val result = McpEditReviewTools.buildProposedEdit(original, "nope", "replaced")
        assertEquals(original, result)
    }

    @Test
    fun `buildProposedEdit replaces only first occurrence`() {
        val original = "aaa\naaa\naaa"
        val result = McpEditReviewTools.buildProposedEdit(original, "aaa", "bbb")
        assertEquals("bbb\naaa\naaa", result)
    }

    @Test
    fun `formatAcceptedResult includes file path`() {
        val result = McpEditReviewTools.formatResult("ACCEPTED", "/src/Foo.kt", null)
        assertTrue(result.contains("ACCEPTED"))
        assertTrue(result.contains("/src/Foo.kt"))
    }

    @Test
    fun `formatRejectedResult includes file path`() {
        val result = McpEditReviewTools.formatResult("REJECTED", "/src/Foo.kt", null)
        assertTrue(result.contains("REJECTED"))
        assertTrue(result.contains("reconsider"))
    }

    @Test
    fun `formatModifiedResult includes content`() {
        val result = McpEditReviewTools.formatResult("MODIFIED", "/src/Foo.kt", "new stuff")
        assertTrue(result.contains("MODIFIED"))
        assertTrue(result.contains("new stuff"))
    }

    @Test
    fun `applyMultiEdit returns content after sequential replacements`() {
        val original = "alpha\nbravo\ncharlie"
        val edits = listOf("alpha" to "one", "bravo" to "two")
        val result = McpEditReviewTools.applyMultiEdit(original, edits)
        assertEquals("one\ntwo\ncharlie", (result as McpEditReviewTools.MultiEditResult.Success).content)
    }

    @Test
    fun `applyMultiEdit feeds earlier edit result into later edit`() {
        val original = "FOO"
        val edits = listOf("FOO" to "BAR", "BAR" to "BAZ")
        val result = McpEditReviewTools.applyMultiEdit(original, edits)
        assertEquals("BAZ", (result as McpEditReviewTools.MultiEditResult.Success).content)
    }

    @Test
    fun `applyMultiEdit returns error with index when edit missing`() {
        val original = "only-alpha"
        val edits = listOf("alpha" to "one", "nope" to "two")
        val result = McpEditReviewTools.applyMultiEdit(original, edits)
        val failure = result as McpEditReviewTools.MultiEditResult.Failure
        assertEquals(1, failure.index)
        assertTrue(failure.message.contains("nope"))
    }

    @Test
    fun `applyMultiEdit errors when edits list is empty`() {
        val result = McpEditReviewTools.applyMultiEdit("abc", emptyList())
        assertEquals(-1, (result as McpEditReviewTools.MultiEditResult.Failure).index)
    }

    @Test
    fun `parseEditsJson returns edits in order`() {
        val json = """[{"old_string":"a","new_string":"A"},{"old_string":"b","new_string":"B"}]"""
        val edits = McpEditReviewTools.parseEditsJson(json)
        assertEquals(listOf("a" to "A", "b" to "B"), edits)
    }

    @Test
    fun `parseEditsJson returns null for malformed JSON`() {
        assertNull(McpEditReviewTools.parseEditsJson("not json"))
    }

    @Test
    fun `parseEditsJson returns null for non-array JSON`() {
        assertNull(McpEditReviewTools.parseEditsJson("""{"edits":[]}"""))
    }

    @Test
    fun `parseEditsJson returns null when entries miss required keys`() {
        assertNull(McpEditReviewTools.parseEditsJson("""[{"old":"a","new":"A"}]"""))
    }

    private val sampleNotebook = """
        {
          "cells": [
            {"cell_type": "code", "id": "cell-1", "source": ["print('one')"]},
            {"cell_type": "markdown", "id": "cell-2", "source": ["# Heading"]}
          ],
          "metadata": {},
          "nbformat": 4,
          "nbformat_minor": 5
        }
    """.trimIndent()

    @Test
    fun `applyNotebookEdit replace updates the targeted cell source`() {
        val result = McpEditReviewTools.applyNotebookEdit(sampleNotebook, "cell-1", "print('updated')", null, "replace")
        val content = (result as McpEditReviewTools.NotebookEditResult.Success).content
        assertTrue(content.contains("print('updated')"))
        assertFalse(content.contains("print('one')"))
    }

    @Test
    fun `applyNotebookEdit replace returns failure for unknown cell id`() {
        val result = McpEditReviewTools.applyNotebookEdit(sampleNotebook, "missing", "x", null, "replace")
        val msg = (result as McpEditReviewTools.NotebookEditResult.Failure).message
        assertTrue(msg.contains("cell-1"))
        assertTrue(msg.contains("cell-2"))
    }

    @Test
    fun `applyNotebookEdit insert adds cell after anchor with provided type`() {
        val result = McpEditReviewTools.applyNotebookEdit(sampleNotebook, "cell-1", "# inserted", "markdown", "insert")
        val content = (result as McpEditReviewTools.NotebookEditResult.Success).content
        assertTrue(content.contains("# inserted"))
        assertTrue(content.indexOf("# inserted") > content.indexOf("print('one')"))
        assertTrue(content.indexOf("# inserted") < content.indexOf("# Heading"))
    }

    @Test
    fun `applyNotebookEdit insert defaults to code when cell_type is null`() {
        val result = McpEditReviewTools.applyNotebookEdit(sampleNotebook, "cell-2", "y = 2", null, "insert")
        val content = (result as McpEditReviewTools.NotebookEditResult.Success).content
        assertTrue(content.contains("y = 2"))
        val yIndex = content.indexOf("y = 2")
        val typeBefore = content.substring(0, yIndex).lastIndexOf("\"cell_type\"")
        val typeSlice = content.substring(typeBefore, yIndex)
        assertTrue("expected code cell_type near inserted cell, slice=$typeSlice", typeSlice.contains("\"code\""))
    }

    @Test
    fun `applyNotebookEdit delete removes the targeted cell`() {
        val result = McpEditReviewTools.applyNotebookEdit(sampleNotebook, "cell-1", "ignored", null, "delete")
        val content = (result as McpEditReviewTools.NotebookEditResult.Success).content
        assertFalse(content.contains("cell-1"))
        assertTrue(content.contains("cell-2"))
    }

    @Test
    fun `applyNotebookEdit returns failure for invalid edit_mode`() {
        val result = McpEditReviewTools.applyNotebookEdit(sampleNotebook, "cell-1", "x", null, "shuffle")
        assertTrue(result is McpEditReviewTools.NotebookEditResult.Failure)
    }

    @Test
    fun `applyNotebookEdit returns failure for malformed JSON`() {
        val result = McpEditReviewTools.applyNotebookEdit("not a notebook", "cell-1", "x", null, "replace")
        assertTrue(result is McpEditReviewTools.NotebookEditResult.Failure)
    }
}
