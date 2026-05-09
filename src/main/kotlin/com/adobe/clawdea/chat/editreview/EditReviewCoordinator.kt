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

import com.adobe.clawdea.chat.MessageRenderer

import com.adobe.clawdea.cli.CliBridge
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/**
 * Manages Layer 2 (fallback) edit review state.
 * Captures file contents when built-in Edit/Write tools are used,
 * provides revert data, and builds feedback messages.
 */
class EditReviewCoordinator(
    private val capturedContents: MutableMap<String, CapturedEdit> = mutableMapOf(),
) {
    // Deprecated constructor for backward compatibility - will be removed when ChatPanel is updated
    @Suppress("UNUSED_PARAMETER")
    constructor(
        project: Project,
        bridge: CliBridge,
        scope: CoroutineScope,
        onStatusUpdate: (String, String) -> Unit,
    ) : this(mutableMapOf())

    private val reviewedEdits = mutableListOf<ReviewedEdit>()
    private val editTimestamps = mutableMapOf<String, EditTimestamp>()

    /**
     * Capture original file content before an Edit/Write tool applies.
     * Called from ChatPanel when a ToolUse event arrives for a built-in edit tool.
     */
    fun captureFileContent(toolUseId: String, filePath: String, content: String, proposedContent: String? = null) {
        capturedContents[toolUseId] = CapturedEdit(filePath, content, proposedContent)
        editTimestamps[toolUseId] = EditTimestamp(filePath, System.currentTimeMillis())
    }

    fun getOriginalContent(toolUseId: String): String? = capturedContents[toolUseId]?.originalContent

    fun getCapturedFilePath(toolUseId: String): String? = capturedContents[toolUseId]?.filePath

    fun getProposedContent(toolUseId: String): String? = capturedContents[toolUseId]?.proposedContent

    fun getEditTimestamp(toolUseId: String): EditTimestamp? = editTimestamps[toolUseId]

    fun clearCapturedContent(toolUseId: String) {
        capturedContents.remove(toolUseId)
    }

    /**
     * Record a Layer 2 review decision for post-turn feedback.
     */
    fun recordDecision(toolUseId: String, outcome: EditOutcome, modifiedContent: String? = null) {
        val captured = capturedContents.remove(toolUseId) ?: return
        reviewedEdits.add(ReviewedEdit(captured.filePath, outcome, modifiedContent))
    }

    /**
     * Check if there are any rejected or modified edits that need feedback.
     */
    fun hasFeedback(): Boolean = reviewedEdits.any { it.outcome != EditOutcome.ACCEPTED }

    /**
     * Build and return feedback, then clear state for next turn.
     */
    fun buildAndClearFeedback(): String? {
        if (reviewedEdits.isEmpty()) return null
        val feedback = buildBatchFeedback(reviewedEdits)
        reviewedEdits.clear()
        return feedback
    }

    /**
     * Clear state for a new turn without sending feedback.
     */
    fun clearForNewTurn() {
        reviewedEdits.clear()
    }

    data class CapturedEdit(
        val filePath: String,
        val originalContent: String,
        val proposedContent: String? = null,
    )

    data class EditTimestamp(val filePath: String, val timestampMs: Long)

    data class ReviewedEdit(
        val filePath: String,
        val outcome: EditOutcome,
        val modifiedContent: String?,
    )

    companion object {
        fun isEditTool(toolName: String): Boolean {
            val lower = toolName.lowercase()
            return lower.contains("edit") || lower.contains("write")
        }

        fun isProposeTool(toolName: String): Boolean {
            val lower = toolName.lowercase()
            return lower.contains("propose_edit") || lower.contains("propose_write")
        }

        fun extractFilePath(toolInput: String): String? {
            return MessageRenderer.extractJsonString(toolInput, "file_path")
                ?: MessageRenderer.extractJsonString(toolInput, "notebook_path")
        }

        fun buildProposedContent(originalContent: String, toolName: String, toolInput: String): String {
            val lower = toolName.lowercase()
            return if (lower.contains("write")) {
                MessageRenderer.extractJsonString(toolInput, "content") ?: originalContent
            } else {
                val oldString = MessageRenderer.extractJsonString(toolInput, "old_string") ?: return originalContent
                val newString = MessageRenderer.extractJsonString(toolInput, "new_string") ?: return originalContent
                if (originalContent.contains(oldString)) {
                    originalContent.replaceFirst(oldString, newString)
                } else {
                    originalContent
                }
            }
        }

        fun buildBatchFeedback(results: List<ReviewedEdit>): String {
            val sb = StringBuilder()
            sb.appendLine("[Edit Review Summary]")
            for (result in results) {
                val line = EditDiffReviewer.buildFeedbackMessage(
                    result.outcome, result.filePath, "", result.modifiedContent,
                )
                if (line != null) {
                    sb.appendLine(line)
                }
            }
            sb.appendLine()
            sb.appendLine("Please continue with these changes in mind.")
            return sb.toString().trim()
        }
    }
}
