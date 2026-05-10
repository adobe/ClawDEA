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

import com.adobe.clawdea.chat.editreview.EditReviewCoordinator
import com.adobe.clawdea.cli.CliBridge
import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.cli.TaskEventExtractor
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.swing.JLabel
import javax.swing.Timer

class EventStreamHandler(
    private val bridge: CliBridge,
    private val renderer: MessageRenderer,
    private val browserRenderer: ChatBrowserRenderer,
    private val editReviewCoordinator: EditReviewCoordinator,
    private val taskWidget: TaskWidgetController,
    private val turnController: TurnController,
    private val statusLabel: JLabel,
    private val scope: CoroutineScope,
    private val onFilesystemRefresh: (path: String) -> Unit,
    private val onContextLabelUpdate: () -> Unit,
    private val onSyncStreamingUi: () -> Unit,
    private val onTurnCompleted: () -> Unit,
    private val onTurnStartStalled: () -> Unit,
    private val onToolResultStalled: () -> Unit,
    private val isUserInputPending: () -> Boolean,
    private val onShowErrorNotification: (message: String) -> Unit,
    private val onTurnSucceeded: () -> Unit = {},
) {
    val messageBuffer = StringBuilder()
    var turnHasContent = false
    var streamStartTime: Long = 0
    var totalTokensUsed = 0

    private val toolNameById = mutableMapOf<String, String>()
    private var toolStartTime: Long = 0
    private val taskExtractor = TaskEventExtractor()
    private val pendingToolUses = mutableMapOf<String, CliEvent.ToolUse>()
    private var progressSequence = 0L

    fun startEventListener() {
        scope.launch {
            bridge.events.collect { event ->
                ApplicationManager.getApplication().invokeLater {
                    handleEvent(event)
                }
            }
        }
    }

    fun watchForTurnStartStall() {
        val observedProgressSequence = progressSequence
        scheduleStallRecovery(TURN_START_STALL_TIMEOUT_MS, observedProgressSequence, onTurnStartStalled)
    }

    private fun scheduleStallRecovery(timeoutMs: Int, observedProgressSequence: Long, onStalled: () -> Unit) {
        Timer(timeoutMs) {
            if (shouldRecoverStall(
                isStreaming = turnController.isStreaming,
                bridgeRunning = bridge.isRunning,
                userInputPending = isUserInputPending(),
                currentProgressSequence = progressSequence,
                observedProgressSequence = observedProgressSequence,
            )) {
                onStalled()
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun handleEvent(event: CliEvent) {
        if (isTurnProgressEvent(event)) {
            progressSequence += 1
        }
        when (event) {
            is CliEvent.SystemInit -> {
                statusLabel.text = "Connected"
            }
            is CliEvent.TextDelta -> {
                messageBuffer.append(event.text)
            }
            is CliEvent.AssistantMessage -> {
                if (messageBuffer.isNotEmpty()) {
                    val bufText = messageBuffer.toString()
                    browserRenderer.appendHtml(renderer.renderAssistantText(bufText))
                    totalTokensUsed += ContextBudgetCalculator.estimateTokens(bufText)
                    messageBuffer.clear()
                    turnHasContent = true
                } else if (event.text.isNotBlank()) {
                    browserRenderer.appendHtml(renderer.renderAssistantText(event.text))
                    totalTokensUsed += ContextBudgetCalculator.estimateTokens(event.text)
                    turnHasContent = true
                }
                for (toolUse in event.toolUses) {
                    toolNameById[toolUse.id] = toolUse.name
                    totalTokensUsed += ContextBudgetCalculator.estimateTokens(toolUse.input)
                    toolStartTime = System.currentTimeMillis()

                    // Render task tools as collapsed blocks; all others as full tool blocks
                    val isTaskTool = toolUse.name in setOf("TaskCreate", "TaskUpdate", "TodoWrite", "TodoRead")
                    if (isTaskTool) {
                        pendingToolUses[toolUse.id] = toolUse
                    } else if (toolUse.name == "AskUserQuestion") {
                        // Rendered by the permission flow as an interactive multi-choice
                        // card — skip the generic tool-use block to avoid duplication.
                    } else if (EditReviewCoordinator.isProposeTool(toolUse.name)) {
                        // Layer 1: MCP propose_edit/propose_write — dialog is shown by MCP handler
                        val filePath = EditReviewCoordinator.extractFilePath(toolUse.input) ?: toolUse.name
                        val file = java.io.File(filePath)
                        val originalContent = if (file.exists()) file.readText() else ""
                        val proposedContent = EditReviewCoordinator.buildProposedContent(originalContent, toolUse.name, toolUse.input)
                        editReviewCoordinator.captureFileContent(toolUse.id, filePath, originalContent, proposedContent)
                        val label = if (toolUse.name.contains("write", ignoreCase = true)) "Write" else "Edit"
                        val initialStatus = if (renderer.autoAcceptEdits) "Auto-accepted" else "Reviewing..."
                        browserRenderer.appendHtml(renderer.renderEditLink(filePath, toolUse.id, initialStatus, label))
                    } else if (EditReviewCoordinator.isEditTool(toolUse.name) && !renderer.autoAcceptEdits) {
                        // Layer 2: built-in Edit/Write slipped through — capture content for revert.
                        // Store proposedContent too so the diff dialog works when the CLI
                        // refuses to apply the edit (e.g. Subscription auth denying built-in
                        // Edit in a mode that doesn't bypass permissions): without it,
                        // current == original and the diff shows 0 changes.
                        val filePath = EditReviewCoordinator.extractFilePath(toolUse.input) ?: toolUse.name
                        val file = java.io.File(filePath)
                        val originalContent = if (file.exists()) file.readText() else ""
                        val proposedContent = EditReviewCoordinator.buildProposedContent(originalContent, toolUse.name, toolUse.input)
                        editReviewCoordinator.captureFileContent(toolUse.id, filePath, originalContent, proposedContent)
                        val label = if (toolUse.name.contains("Write", ignoreCase = true)) "Write" else "Edit"
                        browserRenderer.appendHtml(renderer.renderEditLink(filePath, toolUse.id, "Applied", label, showActions = true))
                    } else if (EditReviewCoordinator.isEditTool(toolUse.name) && renderer.autoAcceptEdits) {
                        // Auto-accept: edit applied by CLI, capture content so diff is viewable on click.
                        // Also store proposedContent as a fallback for the same reason as above.
                        val filePath = EditReviewCoordinator.extractFilePath(toolUse.input) ?: toolUse.name
                        val file = java.io.File(filePath)
                        val originalContent = if (file.exists()) file.readText() else ""
                        val proposedContent = EditReviewCoordinator.buildProposedContent(originalContent, toolUse.name, toolUse.input)
                        editReviewCoordinator.captureFileContent(toolUse.id, filePath, originalContent, proposedContent)
                        val label = if (toolUse.name.contains("Write", ignoreCase = true)) "Write" else "Edit"
                        browserRenderer.appendHtml(renderer.renderEditLink(filePath, toolUse.id, "Auto-accepted", label))
                    } else if (toolUse.name == "Read") {
                        val filePath = MessageRenderer.extractJsonString(toolUse.input, "file_path")
                        if (filePath != null) {
                            browserRenderer.appendHtml(renderer.renderFileLink(filePath, toolUse.id))
                        } else {
                            browserRenderer.appendHtml(renderer.renderToolUse(toolUse.name, toolUse.input, toolUse.id))
                        }
                    } else {
                        browserRenderer.appendHtml(renderer.renderToolUse(toolUse.name, toolUse.input, toolUse.id))
                    }
                }
                onContextLabelUpdate()
            }
            is CliEvent.ToolResult -> {
                // Check for task events
                val pendingToolUse = pendingToolUses.remove(event.toolUseId)
                if (pendingToolUse != null) {
                    val taskEvent = taskExtractor.extract(pendingToolUse, event)
                    if (taskEvent != null) {
                        taskWidget.onTaskEvent(taskEvent)
                        browserRenderer.updateTaskWidget(taskWidget.renderWidget())
                    }
                }

                val elapsed = if (toolStartTime > 0) {
                    val ms = System.currentTimeMillis() - toolStartTime
                    toolStartTime = 0
                    ms
                } else 0L
                browserRenderer.hideStopButton(event.toolUseId)
                if (elapsed > 0) {
                    browserRenderer.injectElapsedTime(event.toolUseId, renderer.formatElapsed(elapsed))
                }
                val toolName = toolNameById.remove(event.toolUseId)
                if (toolName == "Bash") {
                    onFilesystemRefresh("")
                }
                val isEditRelated = toolName != null && (
                    EditReviewCoordinator.isEditTool(toolName) ||
                    EditReviewCoordinator.isProposeTool(toolName)
                )
                if (isEditRelated && !event.isError) {
                    val editedPath = editReviewCoordinator.getCapturedFilePath(event.toolUseId)
                    if (editedPath != null) {
                        onFilesystemRefresh(editedPath)
                    }
                }

                if (isEditRelated) {
                    // For propose_edit/propose_write: update status from result text or MCP outcome store
                    if (EditReviewCoordinator.isProposeTool(toolName!!)) {
                        resolveEditOutcome(event.toolUseId, event.content, 0)
                    }
                    // Suppress output for all edit-related tools
                } else if (toolName == "Read") {
                    // Suppress output for Read — rendered as a compact file link
                } else if (toolName == "AskUserQuestion") {
                    // Suppress output: the answers are already displayed in the
                    // interactive question card and the CLI's textual recap is
                    // redundant.
                } else if (event.content.isNotBlank()) {
                    totalTokensUsed += ContextBudgetCalculator.estimateTokens(event.content)
                    browserRenderer.injectToolOutput(event.toolUseId, renderer.renderToolResult(event.content))
                }
                onContextLabelUpdate()
                watchForToolResultStall(progressSequence)
            }
            is CliEvent.Result -> {
                browserRenderer.hideThinkingIndicator()
                if (messageBuffer.isNotEmpty()) {
                    browserRenderer.appendHtml(renderer.renderAssistantText(messageBuffer.toString()))
                    messageBuffer.clear()
                    turnHasContent = true
                }
                if (event.isError) {
                    val guidance = ErrorEnricher.enrich(event.text)
                    if (guidance != null) {
                        browserRenderer.appendHtml(renderer.renderError(guidance))
                        browserRenderer.appendHtml(renderer.renderInfoMessage(event.text))
                    } else {
                        browserRenderer.appendHtml(renderer.renderError(event.text))
                    }
                    onShowErrorNotification(guidance ?: event.text)
                } else {
                    if (event.text.isNotBlank() && !turnHasContent) {
                        // Show result text only if no assistant content was already rendered
                        browserRenderer.appendHtml(renderer.renderAssistantText(event.text))
                    }
                    // Signal a healthy turn so the prompt-stall escalation counter resets.
                    onTurnSucceeded()
                }
                val totalElapsed = if (streamStartTime > 0) {
                    System.currentTimeMillis() - streamStartTime
                } else 0L
                if (event.costUsd > 0 || totalElapsed > 0) {
                    browserRenderer.appendHtml(renderer.renderCostInfo(event.costUsd, totalElapsed))
                }
                turnController.onStreamResult()
                onSyncStreamingUi()
                browserRenderer.hideAllStopButtons()
                streamStartTime = 0
                statusLabel.text = " "

                // Freeze task widget: final render, then reset for next turn
                if (taskWidget.hasTasks()) {
                    browserRenderer.updateTaskWidget(taskWidget.renderWidget())
                    taskWidget.reset()
                }

                totalTokensUsed += ContextBudgetCalculator.estimateTokens(event.text)
                onContextLabelUpdate()

                // Layer 2 post-turn feedback for rejected/modified edits
                var sentEditFeedback = false
                if (editReviewCoordinator.hasFeedback()) {
                    val feedback = editReviewCoordinator.buildAndClearFeedback()
                    if (feedback != null) {
                        bridge.sendMessage(feedback)
                        turnController.onUserSend()
                        onSyncStreamingUi()
                        streamStartTime = System.currentTimeMillis()
                        statusLabel.text = "Claude is thinking..."
                        watchForTurnStartStall()
                        sentEditFeedback = true
                    }
                }

                if (!sentEditFeedback) {
                    editReviewCoordinator.clearForNewTurn()
                    onTurnCompleted()
                }
            }
            is CliEvent.Unknown -> {
                val raw = event.rawJson.trim()
                if (raw.isBlank()) return
                if (raw.startsWith("{")) {
                    // Attempt to extract "error" field from JSON payloads
                    val errorMatch = Regex(""""error"\s*:\s*"([^"]+)"""").find(raw)
                    if (errorMatch != null) {
                        val errorText = errorMatch.groupValues[1]
                        val guidance = ErrorEnricher.enrich(errorText)
                        if (guidance != null) {
                            browserRenderer.appendHtml(renderer.renderError(guidance))
                            browserRenderer.appendHtml(renderer.renderInfoMessage(errorText))
                        } else {
                            browserRenderer.appendHtml(renderer.renderError(errorText))
                        }
                    }
                } else {
                    browserRenderer.appendHtml(renderer.renderAssistantText(raw))
                }
            }
            is CliEvent.AuthFailure -> Unit // Rendered by the SubscriptionAuth bus listener below.
            // Task events are extracted from ToolUse/ToolResult pairs in the
            // ToolResult branch above, not dispatched through the event flow.
            // These branches exist only for sealed-class exhaustiveness.
            is CliEvent.TaskEvent -> {}
        }
    }

    private fun watchForToolResultStall(toolResultProgressSequence: Long) {
        scheduleStallRecovery(TOOL_RESULT_STALL_TIMEOUT_MS, toolResultProgressSequence, onToolResultStalled)
    }

    /**
     * Resolve the edit outcome for a propose_edit/propose_write tool result.
     * The MCP tool result content is usually empty (the CLI doesn't forward it),
     * so we fall back to EditReviewOutcomes. If the outcome isn't stored yet
     * (race between MCP response and CLI event parsing), retry up to 3 times
     * with 500ms delays.
     */
    private fun resolveEditOutcome(toolUseId: String, content: String, attempt: Int) {
        var status = when {
            content.startsWith("ACCEPTED") -> "Accepted"
            content.startsWith("REJECTED") -> "Rejected"
            content.startsWith("MODIFIED") -> "Modified"
            else -> null
        }

        if (status == null) {
            val filePath = editReviewCoordinator.getCapturedFilePath(toolUseId)
            if (filePath != null) {
                val mcpOutcome = com.adobe.clawdea.chat.editreview.EditReviewOutcomes.take(filePath)
                status = when (mcpOutcome) {
                    "ACCEPTED" -> "Accepted"
                    "AUTO-ACCEPTED" -> "Auto-accepted"
                    "REJECTED" -> "Rejected"
                    "MODIFIED" -> "Modified"
                    else -> null
                }
            }
        }

        if (status != null) {
            browserRenderer.updateEditLinkStatus(toolUseId, status) { renderer.escapeHtml(it) }
            // Don't clear capturedContents here. The user can click the file link
            // any time during the chat session to inspect the diff (Tier 1 path
            // in EditReviewHandler reads from this map). For brand-new files,
            // Local History / git fallbacks (Tier 2) have nothing to recover,
            // so clearing breaks the post-accept review experience entirely.
            // The map gets garbage collected when the chat panel disposes.
        } else if (attempt < 3) {
            Timer(500) { resolveEditOutcome(toolUseId, content, attempt + 1) }.apply {
                isRepeats = false
                start()
            }
        }
    }

    companion object {
        // First-byte latency from Anthropic/Bedrock can spike to ~70-80s when the
        // primer system prompt is large (CLAUDE.md + wiki index + skills + workspace).
        // Keep the watchdog conservative enough to absorb that without false-positives,
        // while still catching genuine hangs within ~1.5 min.
        internal const val TURN_START_STALL_TIMEOUT_MS = 90_000
        internal const val TOOL_RESULT_STALL_TIMEOUT_MS = 90_000

        internal fun shouldRecoverTurnStartStall(
            isStreaming: Boolean,
            bridgeRunning: Boolean,
            userInputPending: Boolean,
            currentProgressSequence: Long,
            observedProgressSequence: Long,
        ): Boolean =
            shouldRecoverStall(
                isStreaming,
                bridgeRunning,
                userInputPending,
                currentProgressSequence,
                observedProgressSequence,
            )

        internal fun shouldRecoverToolResultStall(
            isStreaming: Boolean,
            bridgeRunning: Boolean,
            userInputPending: Boolean,
            currentProgressSequence: Long,
            observedProgressSequence: Long,
        ): Boolean =
            shouldRecoverStall(
                isStreaming,
                bridgeRunning,
                userInputPending,
                currentProgressSequence,
                observedProgressSequence,
            )

        internal fun isTurnProgressEvent(event: CliEvent): Boolean =
            when (event) {
                is CliEvent.TextDelta,
                is CliEvent.AssistantMessage,
                is CliEvent.ToolResult,
                is CliEvent.Result,
                is CliEvent.AuthFailure -> true
                is CliEvent.SystemInit,
                is CliEvent.TaskEvent,
                is CliEvent.Unknown -> false
            }

        private fun shouldRecoverStall(
            isStreaming: Boolean,
            bridgeRunning: Boolean,
            userInputPending: Boolean,
            currentProgressSequence: Long,
            observedProgressSequence: Long,
        ): Boolean =
            isStreaming && bridgeRunning && !userInputPending && currentProgressSequence == observedProgressSequence
    }
}
