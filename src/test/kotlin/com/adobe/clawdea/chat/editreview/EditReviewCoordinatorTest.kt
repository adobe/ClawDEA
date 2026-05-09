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
package com.adobe.clawdea.chat.editreview

import org.junit.Assert.*
import org.junit.Test

class EditReviewCoordinatorTest {

    @Test
    fun `isEditTool returns true for Edit tool`() {
        assertTrue(EditReviewCoordinator.isEditTool("Edit"))
    }

    @Test
    fun `isEditTool returns true for Write tool`() {
        assertTrue(EditReviewCoordinator.isEditTool("Write"))
    }

    @Test
    fun `isEditTool returns false for Read tool`() {
        assertFalse(EditReviewCoordinator.isEditTool("Read"))
    }

    @Test
    fun `isEditTool returns false for Bash tool`() {
        assertFalse(EditReviewCoordinator.isEditTool("Bash"))
    }

    @Test
    fun `isEditTool is case insensitive`() {
        assertTrue(EditReviewCoordinator.isEditTool("edit"))
        assertTrue(EditReviewCoordinator.isEditTool("WRITE"))
        assertTrue(EditReviewCoordinator.isEditTool("NotebookEdit"))
    }

    @Test
    fun `extractFilePath returns path from Edit input`() {
        val input = """{"file_path":"/src/main/Foo.kt","old_string":"a","new_string":"b"}"""
        assertEquals("/src/main/Foo.kt", EditReviewCoordinator.extractFilePath(input))
    }

    @Test
    fun `extractFilePath returns path from Write input`() {
        val input = """{"file_path":"/src/main/Bar.kt","content":"package test"}"""
        assertEquals("/src/main/Bar.kt", EditReviewCoordinator.extractFilePath(input))
    }

    @Test
    fun `extractFilePath returns null for missing path`() {
        val input = """{"command":"ls"}"""
        assertNull(EditReviewCoordinator.extractFilePath(input))
    }

    @Test
    fun `extractFilePath falls back to notebook_path when file_path is absent`() {
        val input = """{"notebook_path":"/nb/demo.ipynb","cell_id":"c-1","new_source":"x"}"""
        assertEquals("/nb/demo.ipynb", EditReviewCoordinator.extractFilePath(input))
    }

    @Test
    fun `buildProposedContent applies edit to original for Edit tool`() {
        val original = "line1\nline2\nline3"
        val input = """{"file_path":"/f.kt","old_string":"line2","new_string":"replaced"}"""
        val proposed = EditReviewCoordinator.buildProposedContent(original, "Edit", input)
        assertEquals("line1\nreplaced\nline3", proposed)
    }

    @Test
    fun `buildProposedContent returns content field for Write tool`() {
        val input = """{"file_path":"/f.kt","content":"brand new file"}"""
        val proposed = EditReviewCoordinator.buildProposedContent("", "Write", input)
        assertEquals("brand new file", proposed)
    }

    @Test
    fun `buildProposedContent returns original when old_string not found`() {
        val original = "line1\nline2"
        val input = """{"file_path":"/f.kt","old_string":"nope","new_string":"replaced"}"""
        val proposed = EditReviewCoordinator.buildProposedContent(original, "Edit", input)
        assertEquals(original, proposed)
    }

    @Test
    fun `buildBatchFeedback combines multiple review results`() {
        val results = listOf(
            EditReviewCoordinator.ReviewedEdit("/a.kt", EditOutcome.ACCEPTED, null),
            EditReviewCoordinator.ReviewedEdit("/b.kt", EditOutcome.REJECTED, null),
            EditReviewCoordinator.ReviewedEdit("/c.kt", EditOutcome.MODIFIED, "modified content"),
        )
        val feedback = EditReviewCoordinator.buildBatchFeedback(results)
        assertTrue(feedback.contains("[Edit Review Summary]"))
        assertTrue(feedback.contains("/a.kt: ACCEPTED"))
        assertTrue(feedback.contains("/b.kt: REJECTED"))
        assertTrue(feedback.contains("/c.kt: MODIFIED"))
        assertTrue(feedback.contains("modified content"))
        assertTrue(feedback.contains("Please continue"))
    }

    @Test
    fun `buildBatchFeedback for single accepted edit`() {
        val results = listOf(
            EditReviewCoordinator.ReviewedEdit("/a.kt", EditOutcome.ACCEPTED, null),
        )
        val feedback = EditReviewCoordinator.buildBatchFeedback(results)
        assertTrue(feedback.contains("ACCEPTED"))
    }

    @Test
    fun `captureFileContent stores content by tool use id`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        coordinator.captureFileContent("toolu_001", "/tmp/test.kt", "original content")
        assertEquals("original content", coordinator.getOriginalContent("toolu_001"))
    }

    @Test
    fun `getOriginalContent returns null for unknown id`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        assertNull(coordinator.getOriginalContent("toolu_999"))
    }

    @Test
    fun `getCapturedFilePath returns path for known id`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        coordinator.captureFileContent("toolu_001", "/tmp/test.kt", "content")
        assertEquals("/tmp/test.kt", coordinator.getCapturedFilePath("toolu_001"))
    }

    @Test
    fun `capturedContents survive clearForNewTurn`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        coordinator.captureFileContent("toolu_050", "/tmp/test.kt", "original")
        coordinator.clearForNewTurn()
        assertEquals("original", coordinator.getOriginalContent("toolu_050"))
        assertEquals("/tmp/test.kt", coordinator.getCapturedFilePath("toolu_050"))
    }

    @Test
    fun `capturedContents survive buildAndClearFeedback`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        coordinator.captureFileContent("toolu_051", "/tmp/test.kt", "original")
        coordinator.recordDecision("toolu_051", EditOutcome.ACCEPTED)
        coordinator.buildAndClearFeedback()
        // The entry for toolu_051 was accepted, so it gets removed by recordDecision
        assertNull(coordinator.getOriginalContent("toolu_051"))

        // But an unreviewed entry should survive
        coordinator.captureFileContent("toolu_052", "/tmp/other.kt", "other content")
        coordinator.recordDecision("toolu_051", EditOutcome.ACCEPTED) // no-op, already gone
        coordinator.buildAndClearFeedback()
        assertEquals("other content", coordinator.getOriginalContent("toolu_052"))
    }

    @Test
    fun `recordDecision removes entry from capturedContents`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        coordinator.captureFileContent("toolu_053", "/tmp/test.kt", "original")
        coordinator.recordDecision("toolu_053", EditOutcome.REJECTED)
        assertNull("entry should be removed after decision", coordinator.getOriginalContent("toolu_053"))
    }

    @Test
    fun `captureFileContent stores proposedContent`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        coordinator.captureFileContent("toolu_060", "/tmp/test.kt", "original", "proposed")
        assertEquals("proposed", coordinator.getProposedContent("toolu_060"))
    }

    @Test
    fun `getProposedContent returns null when not provided`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        coordinator.captureFileContent("toolu_061", "/tmp/test.kt", "original")
        assertNull(coordinator.getProposedContent("toolu_061"))
    }

    @Test
    fun `getProposedContent returns null for unknown id`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        assertNull(coordinator.getProposedContent("toolu_999"))
    }

    @Test
    fun `clearCapturedContent removes entry`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        coordinator.captureFileContent("toolu_062", "/tmp/test.kt", "original", "proposed")
        coordinator.clearCapturedContent("toolu_062")
        assertNull(coordinator.getOriginalContent("toolu_062"))
        assertNull(coordinator.getProposedContent("toolu_062"))
    }

    @Test
    fun `clearCapturedContent is no-op for unknown id`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        coordinator.clearCapturedContent("toolu_999") // should not throw
    }

    @Test
    fun `captureFileContent stores edit timestamp`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        val before = System.currentTimeMillis()
        coordinator.captureFileContent("toolu_070", "/tmp/test.kt", "original")
        val after = System.currentTimeMillis()
        val ts = coordinator.getEditTimestamp("toolu_070")
        assertNotNull(ts)
        assertEquals("/tmp/test.kt", ts!!.filePath)
        assertTrue("timestamp should be >= before", ts.timestampMs >= before)
        assertTrue("timestamp should be <= after", ts.timestampMs <= after)
    }

    @Test
    fun `getEditTimestamp returns null for unknown id`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        assertNull(coordinator.getEditTimestamp("toolu_999"))
    }

    @Test
    fun `editTimestamp survives recordDecision`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        coordinator.captureFileContent("toolu_071", "/tmp/test.kt", "original")
        coordinator.recordDecision("toolu_071", EditOutcome.ACCEPTED)
        // capturedContents is cleared, but timestamp persists
        assertNull(coordinator.getOriginalContent("toolu_071"))
        assertNotNull(coordinator.getEditTimestamp("toolu_071"))
    }

    @Test
    fun `editTimestamp survives clearCapturedContent`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        coordinator.captureFileContent("toolu_072", "/tmp/test.kt", "original")
        coordinator.clearCapturedContent("toolu_072")
        assertNotNull(coordinator.getEditTimestamp("toolu_072"))
    }

    @Test
    fun `editTimestamp survives clearForNewTurn`() {
        val coordinator = EditReviewCoordinator(capturedContents = mutableMapOf())
        coordinator.captureFileContent("toolu_073", "/tmp/test.kt", "original")
        coordinator.clearForNewTurn()
        assertNotNull(coordinator.getEditTimestamp("toolu_073"))
    }
}
