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
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Input for a host patch operation: full original/proposed content.
 */
data class HostPatchInput(
    val filePath: String,
    val originalContent: String,
    val proposedContent: String,
)

/**
 * Permission-gated, reviewed host file patches for the HTTP agent backend.
 *
 * ### Flow
 * 1. Reject paths outside [projectBasePath] (canonicalize + prefix check).
 * 2. Reject stale [HostPatchInput.originalContent] (read current file, compare).
 * 3. Route approval via [SharedToolApprovalGate] (with [autoAcceptEdits]).
 * 4. On approval:
 *    - If [autoAcceptEdits] is true, skip dialog but still validate (steps 1–2).
 *    - Otherwise call [EditDiffReviewer.review] to show diff dialog.
 * 5. On ACCEPTED/MODIFIED outcome, apply content via [EditDiffReviewer.applyContent]
 *    and notify [FilesystemRefreshCoordinator].
 *
 * ### Threading
 * [execute] blocks during approval and diff review — must be called off-EDT.
 */
class HostPatchTool(
    private val projectBasePath: String,
    private val autoAcceptEdits: () -> Boolean,
    private val approvalGate: SharedToolApprovalGate,
    private val reviewer: EditReviewer,
    private val coordinator: RefreshCoordinator,
    private val fileReader: (String) -> String,
) {
    private val log = Logger.getInstance(HostPatchTool::class.java)

    /**
     * Constructor for production use with a [Project].
     */
    constructor(
        project: Project,
        autoAcceptEdits: () -> Boolean,
        approvalGate: SharedToolApprovalGate,
    ) : this(
        projectBasePath = project.basePath ?: throw IllegalArgumentException("Project has no basePath"),
        autoAcceptEdits = autoAcceptEdits,
        approvalGate = approvalGate,
        reviewer = RealEditReviewer(project),
        coordinator = RealRefreshCoordinator(project),
        fileReader = { path ->
            val file = File(path)
            if (!file.exists()) {
                "" // New file
            } else {
                file.readText() // Will throw if unreadable
            }
        },
    )

    /**
     * Execute a patch with approval + validation + review. Returns structured result.
     * Blocks on approval + review — call off-EDT.
     */
    fun execute(input: HostPatchInput, toolUseId: String): ToolExecutionResult {
        // 1. Validate path is within project
        val canonicalPath = try {
            File(input.filePath).canonicalPath
        } catch (e: Exception) {
            return ToolExecutionResult(
                toolCallId = toolUseId,
                content = "Invalid file path: ${e.message}",
                isError = true,
            )
        }

        val canonicalBase = File(projectBasePath).canonicalPath
        if (!canonicalPath.startsWith(canonicalBase)) {
            return ToolExecutionResult(
                toolCallId = toolUseId,
                content = "File path outside project: $canonicalPath",
                isError = true,
            )
        }

        // 2. Validate original content matches current file
        val currentContent = try {
            fileReader(input.filePath)
        } catch (e: Exception) {
            // File exists but can't be read (permission denied, IsADirectory, etc.)
            return ToolExecutionResult(
                toolCallId = toolUseId,
                content = "Cannot read file: ${e.message}",
                isError = true,
            )
        }

        if (currentContent != input.originalContent) {
            return ToolExecutionResult(
                toolCallId = toolUseId,
                content = "File content has changed since edit was proposed (stale patch)",
                isError = true,
            )
        }

        // 3. Route approval
        val inputJson = com.google.gson.JsonObject().apply {
            addProperty("file_path", input.filePath)
        }.toString()

        val approved = approvalGate.approve(
            toolName = "apply_patch",
            inputJson = inputJson,
            toolUseId = toolUseId,
            autoAcceptEdit = autoAcceptEdits(),
            missingRouteBehavior = MissingRouteBehavior.DENY,
        )

        if (!approved) {
            return ToolExecutionResult(
                toolCallId = toolUseId,
                content = "Patch not approved",
                isError = true,
            )
        }

        // 4. Show diff review dialog (unless auto-accept skipped it)
        // Note: path-outside-project and stale-content validation have ALREADY run above (steps 1–2).
        // autoAcceptEdits only skips the interactive diff dialog — never the safety validation.
        // If autoAcceptEdits() is true, the approval gate already short-circuited, so we skip the review dialog.
        if (!autoAcceptEdits()) {
            val reviewResult = reviewer.review(
                filePath = input.filePath,
                originalContent = input.originalContent,
                proposedContent = input.proposedContent,
            )

            when (reviewResult.outcome) {
                EditOutcome.REJECTED, EditOutcome.DISMISSED -> {
                    return ToolExecutionResult(
                        toolCallId = toolUseId,
                        content = "Patch rejected by user",
                        isError = true,
                    )
                }
                EditOutcome.ACCEPTED -> {
                    reviewer.applyContent(input.filePath, input.proposedContent)
                }
                EditOutcome.MODIFIED -> {
                    val modifiedContent = reviewResult.modifiedContent
                        ?: return ToolExecutionResult(
                            toolCallId = toolUseId,
                            content = "Internal error: MODIFIED outcome without modifiedContent",
                            isError = true,
                        )
                    reviewer.applyContent(input.filePath, modifiedContent)
                }
            }
        } else {
            // Auto-accept: apply directly
            reviewer.applyContent(input.filePath, input.proposedContent)
        }

        // 5. Notify filesystem refresh
        coordinator.onEditApplied(input.filePath)

        return ToolExecutionResult(
            toolCallId = toolUseId,
            content = "Patch applied to ${input.filePath}",
            isError = false,
        )
    }

    /**
     * Injectable edit reviewer for testing.
     */
    interface EditReviewer {
        fun review(filePath: String, originalContent: String, proposedContent: String): EditDiffReviewer.ReviewResult
        fun applyContent(filePath: String, content: String)
    }

    /**
     * Injectable refresh coordinator for testing.
     */
    interface RefreshCoordinator {
        fun onEditApplied(filePath: String)
    }

    private class RealEditReviewer(private val project: Project) : EditReviewer {
        private val editDiffReviewer = EditDiffReviewer(project)

        override fun review(
            filePath: String,
            originalContent: String,
            proposedContent: String,
        ): EditDiffReviewer.ReviewResult {
            return editDiffReviewer.review(filePath, originalContent, proposedContent)
        }

        override fun applyContent(filePath: String, content: String) {
            editDiffReviewer.applyContent(filePath, content)
        }
    }

    private class RealRefreshCoordinator(private val project: Project) : RefreshCoordinator {
        override fun onEditApplied(filePath: String) {
            project.getService(FilesystemRefreshCoordinator::class.java).onEditApplied(filePath)
        }
    }
}
