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

class EditDiffReviewerTest {

    @Test
    fun `determineAcceptOutcome returns ACCEPTED when content unchanged`() {
        val proposed = "line1\nline2\nline3"
        val rightContent = "line1\nline2\nline3"
        val outcome = EditDiffReviewer.determineAcceptOutcome(proposed, rightContent)
        assertEquals(EditOutcome.ACCEPTED, outcome)
    }

    @Test
    fun `determineAcceptOutcome returns MODIFIED when content differs`() {
        val proposed = "line1\nline2\nline3"
        val rightContent = "line1\nchanged\nline3"
        val outcome = EditDiffReviewer.determineAcceptOutcome(proposed, rightContent)
        assertEquals(EditOutcome.MODIFIED, outcome)
    }

    @Test
    fun `buildFeedbackMessage for rejection includes file path`() {
        val msg = EditDiffReviewer.buildFeedbackMessage(
            EditOutcome.REJECTED,
            "/src/main/Foo.kt",
            originalContent = "old",
            modifiedContent = null,
        )
        assertNotNull(msg)
        assertTrue(msg!!.contains("/src/main/Foo.kt"))
        assertTrue(msg.contains("REJECTED"))
    }

    @Test
    fun `buildFeedbackMessage for accepted includes file path`() {
        val msg = EditDiffReviewer.buildFeedbackMessage(
            EditOutcome.ACCEPTED,
            "/src/main/Foo.kt",
            originalContent = "old",
            modifiedContent = null,
        )
        assertNotNull(msg)
        assertTrue(msg!!.contains("/src/main/Foo.kt"))
        assertTrue(msg.contains("ACCEPTED"))
    }

    @Test
    fun `buildFeedbackMessage for modification includes modified content when short`() {
        val modified = "line1\nline2\nline3"
        val msg = EditDiffReviewer.buildFeedbackMessage(
            EditOutcome.MODIFIED,
            "/src/main/Foo.kt",
            originalContent = "old",
            modifiedContent = modified,
        )
        assertNotNull(msg)
        assertTrue(msg!!.contains("/src/main/Foo.kt"))
        assertTrue(msg.contains("MODIFIED"))
        assertTrue(msg.contains("line1"))
    }

    @Test
    fun `buildFeedbackMessage truncates modified content over 200 lines`() {
        val lines = (1..250).map { "line $it" }
        val modified = lines.joinToString("\n")
        val msg = EditDiffReviewer.buildFeedbackMessage(
            EditOutcome.MODIFIED,
            "/src/main/Foo.kt",
            originalContent = "old",
            modifiedContent = modified,
        )
        assertNotNull(msg)
        assertTrue(msg!!.contains("250 lines"))
        assertTrue(msg.contains("line 1"))
        assertTrue(msg.contains("line 250"))
        assertFalse(msg.contains("line 100"))
    }

    @Test
    fun `HistoryReviewResult stores outcome and originalContent`() {
        val result = EditDiffReviewer.HistoryReviewResult(
            outcome = EditOutcome.ACCEPTED,
            originalContent = "original file content",
            modifiedContent = null,
        )
        assertEquals(EditOutcome.ACCEPTED, result.outcome)
        assertEquals("original file content", result.originalContent)
        assertNull(result.modifiedContent)
    }

    @Test
    fun `HistoryReviewResult stores modifiedContent when modified`() {
        val result = EditDiffReviewer.HistoryReviewResult(
            outcome = EditOutcome.MODIFIED,
            originalContent = "original",
            modifiedContent = "user changed this",
        )
        assertEquals(EditOutcome.MODIFIED, result.outcome)
        assertEquals("user changed this", result.modifiedContent)
    }
}
