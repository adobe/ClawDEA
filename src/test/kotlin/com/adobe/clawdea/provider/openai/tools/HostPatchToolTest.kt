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
package com.adobe.clawdea.provider.openai.tools

import com.adobe.clawdea.chat.FilesystemRefreshCoordinator
import com.adobe.clawdea.chat.editreview.EditDiffReviewer
import com.adobe.clawdea.chat.editreview.EditOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPatchToolTest {

    @Test
    fun `path outside project is rejected`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "allow-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val reviewer = FakeReviewer(EditOutcome.ACCEPTED, null)
        val coordinator = FakeCoordinator()
        val tool = HostPatchTool(
            projectBasePath = "/project",
            autoAcceptEdits = { false },
            approvalGate = gate,
            reviewer = reviewer,
            coordinator = coordinator,
            fileReader = { "" },
        )

        val result = tool.execute(
            HostPatchInput("/outside/file.txt", "old", "new"),
            "tool-1",
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("outside project"))
        assertEquals(0, reviewer.reviewedPaths.size)
    }

    @Test
    fun `stale original content is rejected`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "allow-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val reviewer = FakeReviewer(EditOutcome.ACCEPTED, null)
        val coordinator = FakeCoordinator()
        val tool = HostPatchTool(
            projectBasePath = "/project",
            autoAcceptEdits = { false },
            approvalGate = gate,
            reviewer = reviewer,
            coordinator = coordinator,
            fileReader = { "current content" },
        )

        val result = tool.execute(
            HostPatchInput("/project/file.txt", "stale", "new"),
            "tool-1",
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("stale") || result.content.contains("changed"))
        assertEquals(0, reviewer.reviewedPaths.size)
    }

    @Test
    fun `denied approval returns denied result`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "confirm-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val reviewer = FakeReviewer(EditOutcome.ACCEPTED, null)
        val coordinator = FakeCoordinator()
        val tool = HostPatchTool(
            projectBasePath = "/project",
            autoAcceptEdits = { false },
            approvalGate = gate,
            reviewer = reviewer,
            coordinator = coordinator,
            fileReader = { "old" },
        )

        val result = tool.execute(
            HostPatchInput("/project/file.txt", "old", "new"),
            "tool-1",
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("denied") || result.content.contains("not approved"))
        assertEquals(0, reviewer.reviewedPaths.size)
        assertEquals(0, reviewer.appliedPaths.size)
    }

    @Test
    fun `rejected review returns rejected result`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "allow-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val reviewer = FakeReviewer(EditOutcome.REJECTED, null)
        val coordinator = FakeCoordinator()
        val tool = HostPatchTool(
            projectBasePath = "/project",
            autoAcceptEdits = { false },
            approvalGate = gate,
            reviewer = reviewer,
            coordinator = coordinator,
            fileReader = { "old" },
        )

        val result = tool.execute(
            HostPatchInput("/project/file.txt", "old", "new"),
            "tool-1",
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("rejected"))
        assertEquals(1, reviewer.reviewedPaths.size)
        assertEquals(0, reviewer.appliedPaths.size)
    }

    @Test
    fun `accepted review applies content`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "allow-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val reviewer = FakeReviewer(EditOutcome.ACCEPTED, null)
        val coordinator = FakeCoordinator()
        val tool = HostPatchTool(
            projectBasePath = "/project",
            autoAcceptEdits = { false },
            approvalGate = gate,
            reviewer = reviewer,
            coordinator = coordinator,
            fileReader = { "old" },
        )

        val result = tool.execute(
            HostPatchInput("/project/file.txt", "old", "new"),
            "tool-1",
        )
        assertEquals(false, result.isError)
        assertTrue(result.content.contains("applied"))
        assertEquals(1, reviewer.reviewedPaths.size)
        assertEquals(1, reviewer.appliedPaths.size)
        assertEquals("/project/file.txt", reviewer.appliedPaths[0])
        assertEquals(1, coordinator.refreshedFiles.size)
    }

    @Test
    fun `modified review applies modified content`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "allow-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val reviewer = FakeReviewer(EditOutcome.MODIFIED, "modified by user")
        val coordinator = FakeCoordinator()
        val tool = HostPatchTool(
            projectBasePath = "/project",
            autoAcceptEdits = { false },
            approvalGate = gate,
            reviewer = reviewer,
            coordinator = coordinator,
            fileReader = { "old" },
        )

        val result = tool.execute(
            HostPatchInput("/project/file.txt", "old", "new"),
            "tool-1",
        )
        assertEquals(false, result.isError)
        assertTrue(result.content.contains("applied") || result.content.contains("modified"))
        assertEquals(1, reviewer.reviewedPaths.size)
        assertEquals(1, reviewer.appliedPaths.size)
        assertEquals("modified by user", reviewer.appliedContent[0])
    }

    @Test
    fun `autoAcceptEdits skips dialog but still validates`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "allow-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val reviewer = FakeReviewer(EditOutcome.ACCEPTED, null)
        val coordinator = FakeCoordinator()
        val tool = HostPatchTool(
            projectBasePath = "/project",
            autoAcceptEdits = { true },
            approvalGate = gate,
            reviewer = reviewer,
            coordinator = coordinator,
            fileReader = { "old" },
        )

        // Stale content still rejected even with auto-accept
        val result = tool.execute(
            HostPatchInput("/project/file.txt", "stale", "new"),
            "tool-1",
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("stale") || result.content.contains("changed"))
    }

    @Test
    fun `file read error returns error result`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "allow-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val reviewer = FakeReviewer(EditOutcome.ACCEPTED, null)
        val coordinator = FakeCoordinator()
        val tool = HostPatchTool(
            projectBasePath = "/project",
            autoAcceptEdits = { false },
            approvalGate = gate,
            reviewer = reviewer,
            coordinator = coordinator,
            fileReader = { throw java.io.IOException("Permission denied") },
        )

        val result = tool.execute(
            HostPatchInput("/project/file.txt", "old", "new"),
            "tool-1",
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("Cannot read"))
        assertEquals(0, reviewer.reviewedPaths.size)
    }

    /** Fake reviewer for testing. */
    private class FakeReviewer(
        private val outcome: EditOutcome,
        private val modifiedContent: String?,
    ) : HostPatchTool.EditReviewer {
        val reviewedPaths = mutableListOf<String>()
        val appliedPaths = mutableListOf<String>()
        val appliedContent = mutableListOf<String>()

        override fun review(
            filePath: String,
            originalContent: String,
            proposedContent: String,
        ): EditDiffReviewer.ReviewResult {
            reviewedPaths.add(filePath)
            return EditDiffReviewer.ReviewResult(outcome, modifiedContent)
        }

        override fun applyContent(filePath: String, content: String) {
            appliedPaths.add(filePath)
            appliedContent.add(content)
        }
    }

    /** Fake coordinator for testing. */
    private class FakeCoordinator : HostPatchTool.RefreshCoordinator {
        val refreshedFiles = mutableListOf<String>()

        override fun onEditApplied(filePath: String) {
            refreshedFiles.add(filePath)
        }
    }
}
