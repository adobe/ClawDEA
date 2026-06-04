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
    private val subAgentController: SubAgentController,
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
    /**
     * Consults the project-level [com.adobe.clawdea.chat.permission.AutoAllowSignal].
     * Returns true (and consumes the signal) when the (toolName, inputJson) pair was
     * silently allowed by "Allow all" — used to flag the matching tool block.
     * Routing through the ToolUse event guarantees the marker lands in this panel,
     * not whichever tab is focused at the moment the MCP handler decides.
     */
    private val consumeAutoAllow: (toolUseId: String, toolName: String, inputJson: String) -> Boolean = { _, _, _ -> false },
    private val isToolAutoAllowed: (toolName: String) -> Boolean = { _ -> false },
) {
    val messageBuffer = StringBuilder()
    var turnHasContent = false
    var streamStartTime: Long = 0
    var totalTokensUsed = 0
    // Context window for the model in use, as reported by CC's `result.modelUsage`.
    // 0 until the first turn completes — ChatPanel falls back to a default until then.
    var contextWindow = 0
    var lastAssistantText: String = ""

    private val toolNameById = mutableMapOf<String, String>()
    private val toolInputById = mutableMapOf<String, String>()
    private val autoAllowedToolIds = mutableSetOf<String>()
    private var toolStartTime: Long = 0
    private val taskExtractor = TaskEventExtractor()
    private val pendingToolUses = mutableMapOf<String, CliEvent.ToolUse>()
    private var progressSequence = 0L

    /**
     * Recent ToolUse events keyed by `(toolName + 0x00 + inputJson)` so the
     * project-level [com.adobe.clawdea.chat.permission.PermissionRouterRegistry]
     * can ask "is this call yours?" when the MCP `request_permission` arrives.
     * The CLI emits `ToolUse` to stream-json before invoking
     * `request_permission`, so the lookup races cleanly: the panel that just
     * processed the ToolUse holds the entry, others don't.
     *
     * Entries are removed when the matching ToolResult arrives (or pruned by
     * size — a runaway turn must not grow this map forever).
     */
    private val routableToolUses = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Visible to the panel for [PermissionRouter] registration. Preferred path:
     * id-based lookup. Crucially does NOT remove the entry — CC retries
     * `request_permission` after the 45 s dispatcher timeout and the retry
     * must still find this panel. Entries are dropped at ToolResult time.
     */
    fun claimPermissionById(toolUseId: String): Boolean =
        routableToolUses.containsKey(toolUseId)

    /**
     * Fallback used only when CC doesn't pass `tool_use_id` to
     * `request_permission` (older versions, stdio SDK path). Linear scan
     * since this map is bounded to in-flight calls per panel.
     */
    fun claimPermission(toolName: String, inputJson: String): String? {
        val needle = toolName + " " + inputJson
        for ((id, value) in routableToolUses) {
            if (value == needle) return id
        }
        return null
    }

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
                // Sub-agent reasoning tokens must not leak into the main bubble;
                // the child `assistant` message carries the full text and is
                // rendered into the sub-agent card instead.
                if (subAgentController.parentCardFor(event.parentToolUseId) == null) {
                    messageBuffer.append(event.text)
                }
            }
            is CliEvent.AssistantMessage -> {
                val parentCard = subAgentController.parentCardFor(event.parentToolUseId)
                if (parentCard != null) {
                    // Inner content of a running sub-agent.
                    if (messageBuffer.isNotEmpty()) messageBuffer.clear()  // guard: discard any buffered main-agent text before rendering inner content
                    if (event.text.isNotBlank()) {
                        browserRenderer.appendIntoSubAgent(parentCard, renderer.renderAssistantText(event.text))
                    }
                    for (toolUse in event.toolUses) {
                        toolNameById[toolUse.id] = toolUse.name
                        toolInputById[toolUse.id] = toolUse.input
                        routableToolUses[toolUse.id] = toolUse.name + " " + toolUse.input
                        toolStartTime = System.currentTimeMillis()
                        val count = subAgentController.recordStep(parentCard)

                        // Inner edit/propose tools: capture content so the diff and
                        // filesystem refresh work, and render a clickable edit-link
                        // (renderToolUseEvent) rather than a bare step row. All the
                        // result-time hooks (resolveEditOutcome, getCapturedFilePath,
                        // updateEditLinkStatus) key off data-tool-id globally, so they
                        // operate correctly on the nested link.
                        val isEditOrPropose = EditReviewCoordinator.isProposeTool(toolUse.name) ||
                            EditReviewCoordinator.isEditTool(toolUse.name)
                        val stepHtml = if (isEditOrPropose) {
                            val filePath = EditReviewCoordinator.extractFilePath(toolUse.input) ?: toolUse.name
                            val file = java.io.File(filePath)
                            val originalContent = if (file.exists()) file.readText() else ""
                            val proposedContent = EditReviewCoordinator.buildProposedContent(originalContent, toolUse.name, toolUse.input)
                            editReviewCoordinator.captureFileContent(toolUse.id, filePath, originalContent, proposedContent)
                            renderer.renderToolUseEvent(
                                toolName = toolUse.name,
                                input = toolUse.input,
                                toolUseId = toolUse.id,
                                mode = ToolMode.Live(autoAcceptEdits = renderer.autoAcceptEdits),
                            )
                        } else {
                            renderer.renderInnerToolUse(toolUse.name, toolUse.input, toolUse.id)
                        }
                        if (stepHtml.isNotBlank()) browserRenderer.appendIntoSubAgent(parentCard, stepHtml)
                        browserRenderer.updateSubAgentStatus(parentCard, "&#9203; running &middot; $count steps")
                    }
                    onContextLabelUpdate()
                    return
                }

                // Top-level (main agent) content.
                if (messageBuffer.isNotEmpty()) {
                    val bufText = messageBuffer.toString()
                    browserRenderer.appendHtml(renderer.renderAssistantText(bufText))
                    lastAssistantText = bufText
                    messageBuffer.clear()
                    turnHasContent = true
                } else if (event.text.isNotBlank()) {
                    browserRenderer.appendHtml(renderer.renderAssistantText(event.text))
                    lastAssistantText = event.text
                    turnHasContent = true
                }
                for (toolUse in event.toolUses) {
                    toolNameById[toolUse.id] = toolUse.name
                    toolInputById[toolUse.id] = toolUse.input
                    routableToolUses[toolUse.id] = toolUse.name + " " + toolUse.input
                    // Note: auto-allow consumption is deferred to ToolResult time.
                    // The MCP `request_permission` handler races with this
                    // AssistantMessage on the EDT — under "Allow all" the CLI
                    // emits ToolUse and calls request_permission concurrently,
                    // and the EDT often processes ToolUse before the HTTP
                    // handler runs AutoAllowSignal.notify(). Consuming here
                    // would miss the signal and the marker never renders.
                    // ToolResult arrives strictly after request_permission
                    // returns, so the signal is reliably present by then.
                    toolStartTime = System.currentTimeMillis()

                    if (SubAgentController.isSubAgentTool(toolUse.name)) {
                        val agentType = MessageRenderer.extractJsonString(toolUse.input, "subagent_type") ?: "agent"
                        val description = MessageRenderer.extractJsonString(toolUse.input, "description") ?: ""
                        subAgentController.register(toolUse.id, agentType, description, System.currentTimeMillis())
                        browserRenderer.appendHtml(renderer.renderSubAgentCard(agentType, description, toolUse.id))
                        continue
                    }

                    // Side effects that only make sense live: track for the
                    // task widget, capture edit content for diff/revert.
                    val isTaskTool = toolUse.name in MessageRenderer.TASK_TOOLS
                    if (isTaskTool) {
                        pendingToolUses[toolUse.id] = toolUse
                    } else if (EditReviewCoordinator.isProposeTool(toolUse.name) ||
                        EditReviewCoordinator.isEditTool(toolUse.name)) {
                        // Store proposedContent so the diff dialog works even
                        // when the CLI refuses to apply the edit (Subscription
                        // auth denying built-in Edit in a non-bypass mode):
                        // without it, current == original and the diff shows
                        // zero changes.
                        val filePath = EditReviewCoordinator.extractFilePath(toolUse.input) ?: toolUse.name
                        val file = java.io.File(filePath)
                        val originalContent = if (file.exists()) file.readText() else ""
                        val proposedContent = EditReviewCoordinator.buildProposedContent(originalContent, toolUse.name, toolUse.input)
                        editReviewCoordinator.captureFileContent(toolUse.id, filePath, originalContent, proposedContent)
                    }

                    // Single source of truth for the HTML — same routing the
                    // replay path uses, just in Live mode.
                    val html = renderer.renderToolUseEvent(
                        toolName = toolUse.name,
                        input = toolUse.input,
                        toolUseId = toolUse.id,
                        mode = ToolMode.Live(autoAcceptEdits = renderer.autoAcceptEdits),
                    )
                    if (html.isNotBlank()) browserRenderer.appendHtml(html)
                }
                onContextLabelUpdate()
            }
            is CliEvent.ToolResult -> {
                // The sub-agent's own result: collapse its card to a summary.
                if (subAgentController.isActive(event.toolUseId)) {
                    val status = if (event.isError) SubAgentController.Status.ERROR else SubAgentController.Status.DONE
                    val state = subAgentController.finalize(event.toolUseId, status)
                    if (state != null) {
                        browserRenderer.finalizeSubAgent(
                            event.toolUseId,
                            renderer.renderSubAgentSummary(status, state.stepCount, event.content),
                        )
                    }
                    routableToolUses.remove(event.toolUseId)
                    toolNameById.remove(event.toolUseId)
                    toolInputById.remove(event.toolUseId)
                    toolStartTime = 0
                    onContextLabelUpdate()
                    watchForToolResultStall(progressSequence)
                    return
                }

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
                val toolInput = toolInputById.remove(event.toolUseId)
                // Mark as auto-allowed if either the signal was already
                // posted (slow tools) or the mode says it would have been
                // (fast tools where ToolResult arrives before notify()).
                val consumed = toolName != null && toolInput != null &&
                    (consumeAutoAllow(event.toolUseId, toolName, toolInput) ||
                        isToolAutoAllowed(toolName))
                if (consumed) {
                    autoAllowedToolIds.add(event.toolUseId)
                }
                // Drop the permission-routing entry for this tool call. Claims
                // do not remove (CC may retry request_permission after the 45 s
                // dispatcher timeout); ToolResult is the authoritative end of
                // the call, so the map can't grow forever.
                routableToolUses.remove(event.toolUseId)
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
                    val autoAllowed = autoAllowedToolIds.remove(event.toolUseId)
                    browserRenderer.injectToolOutput(
                        event.toolUseId,
                        renderer.renderToolResult(event.content, autoAllowed),
                    )
                }
                onContextLabelUpdate()
                watchForToolResultStall(progressSequence)
            }
            is CliEvent.Result -> {
                browserRenderer.hideThinkingIndicator()
                // Any sub-agent still active at turn end was aborted/interrupted —
                // finalize its card into an aborted state (stays expanded).
                for (id in subAgentController.activeIds()) {
                    val state = subAgentController.finalize(id, SubAgentController.Status.ABORTED)
                    if (state != null) {
                        browserRenderer.updateSubAgentStatus(id, "&#9632; aborted")
                    }
                }
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

                // Replace the stream-side estimate with CC's authoritative number
                // when available. Falls back to the running estimate only when CC
                // didn't include a `usage` object (older versions / format changes).
                if (event.contextTokens > 0) {
                    totalTokensUsed = event.contextTokens
                } else {
                    totalTokensUsed += ContextBudgetCalculator.estimateTokens(event.text)
                }
                if (event.contextWindow > 0) {
                    contextWindow = event.contextWindow
                }
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
            // /goal Stop-hook feedback: no UI action needed here; higher-level
            // goal orchestration (next turn dispatch) will handle it separately.
            is CliEvent.GoalFeedback -> {}
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
        // First-byte latency from Anthropic/Bedrock can spike to 2+ minutes when the
        // system prompt is large (CLAUDE.md + wiki + skills + workspace + tool results).
        // The LLM needs to process all prior context before emitting the next token.
        // 180s absorbs slow API responses without false-positives.
        internal const val TURN_START_STALL_TIMEOUT_MS = 180_000
        internal const val TOOL_RESULT_STALL_TIMEOUT_MS = 180_000

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
                is CliEvent.GoalFeedback,
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
