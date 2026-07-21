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
    private val goalController: GoalController,
    private val turnController: TurnController,
    private val statusLabel: JLabel,
    private val scope: CoroutineScope,
    private val onFilesystemRefresh: (path: String) -> Unit,
    private val onContextLabelUpdate: () -> Unit,
    private val onSyncStreamingUi: () -> Unit,
    private val onTurnBecameIdle: () -> Boolean,
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
    private val costTracker: com.adobe.clawdea.cost.CostTracker,
    private val savingsTracker: com.adobe.clawdea.cost.SavingsTracker,
    /** Stable per-tab id for per-chat cost accounting. */
    private val chatId: String,
    private val resolveEffort: () -> String,
    /** True when no explicit model is selected (the CLI uses its Default). */
    private val resolveIsDefaultModel: () -> Boolean = { false },
    /**
     * Display model for the turn footer when the stream itself carries no model id.
     * Claude's `stream-json` always reports the resolved model, but codex's
     * `exec --json` does not, so the footer falls back to the user's selection
     * (or "Default") to still show model + effort.
     */
    private val resolveModelLabel: () -> String = { "" },
) {
    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(EventStreamHandler::class.java)

    val messageBuffer = StringBuilder()
    var turnHasContent = false
    var streamStartTime: Long = 0

    // Reasoning ("Thinking") deltas arrive token-by-token from codex. Rendering each one with its
    // own JCEF executeJavaScript call floods the EDT during reasoning-heavy turns (hundreds of IPC
    // round-trips + layout thrash → visible IDE sluggishness). Coalesce them in Kotlin and flush to
    // the browser on a short throttle. EDT-confined (handleEvent and the Swing Timer both run on the
    // EDT), so no locking is needed.
    private val reasoningBuffer = StringBuilder()
    private var reasoningFlushTimer: Timer? = null

    // Set when a genuine CliEvent.Result ends the turn; cleared as soon as a new turn streams.
    // Gates the false-stall self-heal ([shouldRestoreStreaming]) so a stray coarse event arriving
    // after a real turn end can't resurrect the activity indicator (leaving its pause button stuck).
    // EDT-confined like the rest of this handler.
    private var turnGenuinelyEnded = false

    /** Flushes any buffered reasoning text to the browser in a single JCEF call. EDT-only. */
    private fun flushReasoning() {
        reasoningFlushTimer?.stop()
        reasoningFlushTimer = null
        if (reasoningBuffer.isEmpty()) return
        browserRenderer.appendReasoningDelta(reasoningBuffer.toString())
        reasoningBuffer.setLength(0)
    }

    /** Arms a one-shot flush if none is pending, coalescing bursts of deltas into one render. */
    private fun scheduleReasoningFlush() {
        if (reasoningFlushTimer != null) return
        reasoningFlushTimer = Timer(REASONING_FLUSH_MS) { flushReasoning() }.apply {
            isRepeats = false
            start()
        }
    }

    /**
     * Knowledge-upkeep bucket classified from the prompt that started the in-flight turn,
     * captured at submit time and consumed when the turn's Result arrives. Null for an
     * ordinary turn. See [com.adobe.clawdea.cost.KnowledgeBucketClassifier].
     */
    private var pendingKnowledgeBucket: com.adobe.clawdea.cost.KnowledgeBucket? = null

    // --- Savings-estimation per-turn accumulators (top-level only; reset each Result) ---
    private val turnIndexToolHits = mutableListOf<com.adobe.clawdea.cost.IndexToolObservation>()
    // toolUseId -> index-tool name, for sizing the matching ToolResult.
    private val pendingIndexToolUses = mutableMapOf<String, String>()
    // Subagent dispatches this turn (wiki-librarian, etc.) for the librarian lever.
    private val turnSubagents = mutableListOf<com.adobe.clawdea.cost.SubagentObservation>()
    // Subagent card id -> tokens read by inner tool results (Read / MCP) this turn.
    private val subagentReadTokens = mutableMapOf<String, Int>()

    /**
     * Called by the panel right before a user-originated prompt is sent to the bridge.
     * [explicitBucket] lets the caller name the knowledge bucket directly — required for
     * slash commands that dispatch an *expanded template* (e.g. /seed-wiki sends a long
     * "Bootstrap an initial wiki…" prompt, not the literal "/seed-wiki"), which the text
     * classifier can't recognize. Falls back to classifying the prompt text when null.
     */
    fun onTurnSubmitted(
        promptText: String,
        explicitBucket: com.adobe.clawdea.cost.KnowledgeBucket? = null,
    ) {
        pendingKnowledgeBucket = explicitBucket
            ?: com.adobe.clawdea.cost.KnowledgeBucketClassifier.classify(promptText)
        turnIndexToolHits.clear()
        pendingIndexToolUses.clear()
        turnSubagents.clear()
        subagentReadTokens.clear()
    }

    /**
     * Conservative primer-overhead proxy: the wiki TOC + REPO_STATE are a small, roughly FIXED slice
     * of the cached prefix ClawDEA ships each turn. We take a flat fraction of the turn's cache tokens
     * but cap it at [PRIMER_TOKENS_CEILING] — the primer is fixed-size, so on huge-context turns a raw
     * percentage would overstate it. Kept conservative so the cost lever never overstates the debit.
     */
    private fun primerTokensFrom(turnCacheTokens: Int): Int =
        (turnCacheTokens * PRIMER_CACHE_FRACTION).toInt().coerceAtMost(PRIMER_TOKENS_CEILING)

    var totalTokensUsed = 0
    // Context window for the model in use, as reported by CC's `result.modelUsage`.
    // 0 until the first turn completes — ChatPanel falls back to a default until then.
    var contextWindow = 0
    var lastAssistantText: String = ""

    /** Real model id for the active session, from the latest SystemInit. */
    var currentModel: String = ""

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
        // A live turn is streaming again → clear the "genuinely ended" latch so the self-heal is
        // re-armed for this new turn. Set only by a real Result (below); never by the self-heal.
        if (turnController.isStreaming) turnGenuinelyEnded = false
        // Self-heal a falsely-ended turn. A long silent gap (large context → slow
        // first byte after a tool result) can trip the stall watchdog, which resets
        // turn state, hides the indicator, and restarts the bridge. If that restart
        // fails (e.g. a slow shell-env capture) the ORIGINAL CLI keeps streaming, so
        // coarse events keep arriving while isStreaming is false — activity with no
        // visible hint (the exact "still running, no indicator" symptom). Any coarse
        // event proves the turn is alive, so re-enter streaming and re-arm the
        // watchdog. Cannot misfire after a genuine Result: that is terminal and no
        // AssistantMessage/ToolResult follows until the next user send (which sets
        // streaming true itself).
        if (shouldRestoreStreaming(event, turnController.isStreaming, turnController.isPaused, bridge.isRunning, turnGenuinelyEnded)) {
            log.warn("activity after turn end — CLI still streaming; restoring turn UI (recovered false stall)")
            turnController.setStreaming(true)
            onSyncStreamingUi()
            watchForToolResultStall(progressSequence)
        }
        // Re-assert the activity indicator on any agent activity while the turn is
        // still live. It is shown once at submit and removed at turn end, but
        // several mid-session paths (resume, wake recovery, restart, stall) can
        // drop it — and JCEF can stop painting it during a long sub-agent run —
        // leaving activity with no visible hint. Poking on each message/tool
        // result heals it without resurrecting it after the turn (Result is
        // excluded and the gate is streaming && !paused).
        if (shouldPokeIndicator(event, turnController.isStreaming, turnController.isPaused)) {
            browserRenderer.pokeThinkingIndicator()
        }
        when (event) {
            is CliEvent.SystemInit -> {
                currentModel = event.model
                statusLabel.text = "Connected"
            }
            is CliEvent.TextDelta -> {
                // Only the main agent (null parent) streams into the shared main
                // buffer. Any delta that carries a parent — a depth-1 sub-agent
                // OR a deeper nested one whose card we don't track — must never
                // leak into the main bubble: the child `assistant` message
                // carries the full text and is rendered into its own card. This
                // keeps parallel sub-agent output from corrupting the main
                // stream mid-phrase.
                if (isMainAgentStream(event.parentToolUseId)) {
                    messageBuffer.append(event.text)
                }
            }
            is CliEvent.ReasoningDelta -> {
                // Model reasoning/thinking tokens (when exposed by the active backend;
                // Claude's stream drops them). Streamed through the shared "Thinking"
                // indicator without mixing into messageBuffer, which holds the answer text. Buffered
                // and throttled to the browser — see [reasoningBuffer]/[flushReasoning].
                reasoningBuffer.append(event.text)
                scheduleReasoningFlush()
            }
            is CliEvent.AssistantMessage -> {
                val parentCard = subAgentController.parentCardFor(event.parentToolUseId)
                if (parentCard != null) {
                    // Inner content of a running sub-agent. Do NOT touch the
                    // main buffer here — a parallel main-agent phrase may be
                    // mid-stream, and clearing it would truncate the main
                    // output. Main text flushes in order at the next top-level
                    // assistant message or at Result.
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
                        val isReadWithPath = toolUse.name == "Read" &&
                            MessageRenderer.extractJsonString(toolUse.input, "file_path") != null
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
                        } else if (isReadWithPath) {
                            // Match main-chat behavior: compact filename link, not
                            // a step row showing the full path.
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

                // Top-level (main agent) content. Capture the model here as the
                // authoritative source for the cost footer: every assistant message
                // carries message.model, whereas SystemInit.model is blank on resume
                // and absent in some CC init shapes.
                if (event.model.isNotBlank()) {
                    currentModel = event.model
                }
                for (tu in event.toolUses) {
                    if (com.adobe.clawdea.cost.TurnObservationBuilder.isIndexTool(tu.name)) {
                        pendingIndexToolUses[tu.id] = tu.name
                    }
                }
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
                        // Live sub-agent cards are pinned in the bottom dock
                        // (stacked when several run in parallel) so they stay
                        // visible while active; finalizeSubAgent releases the
                        // card back into the message flow once it completes.
                        browserRenderer.startActiveAgent(renderer.renderSubAgentCard(agentType, description, toolUse.id))
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
                // Accumulate inner reads for an active subagent (feeds librarian lever).
                val subagentCard = event.parentToolUseId?.takeIf { subAgentController.isActive(it) }
                if (subagentCard != null && !subAgentController.isActive(event.toolUseId)) {
                    val tokens = com.adobe.clawdea.cost.TurnObservationBuilder.estimateTokens(event.content)
                    subagentReadTokens.merge(subagentCard, tokens) { a, b -> a + b }
                }
                // Size any top-level index-tool result this turn.
                val idxName = pendingIndexToolUses.remove(event.toolUseId)
                    ?: toolNameById[event.toolUseId]?.takeIf { com.adobe.clawdea.cost.TurnObservationBuilder.isIndexTool(it) }
                idxName?.let { name ->
                    val tokens = com.adobe.clawdea.cost.TurnObservationBuilder.estimateTokens(event.content)
                    val hits = (tokens / 100).coerceAtLeast(1)
                    turnIndexToolHits.add(com.adobe.clawdea.cost.IndexToolObservation(name, hits, tokens))
                }
                // A background (run_in_background) Agent's own tool_result is a
                // near-instant launch ack, NOT a completion — its inner steps and
                // real completion stream afterward (often in later turns). The CLI
                // marks the card background via a `system/task_started` event that
                // arrives just BEFORE this ack, so by now the card is flagged. Keep
                // it open on the ack; the real close comes from task_notification
                // (see the BackgroundTask branch). Finalizing here would drop the
                // card before its steps arrive and they would leak to the main chat.
                if (subAgentController.isActive(event.toolUseId) &&
                    subAgentController.isLaunchedInBackground(event.toolUseId)
                ) {
                    log.debug("subagent: launch ack for background card id=${event.toolUseId} → keep open")
                    // Drop only the permission-routing entry; keep name/input maps so
                    // inner steps continue to resolve. Do not finalize.
                    routableToolUses.remove(event.toolUseId)
                    onContextLabelUpdate()
                    watchForToolResultStall(progressSequence)
                    return
                }
                // The sub-agent's own result: collapse its card to a summary.
                if (subAgentController.isActive(event.toolUseId)) {
                    val status = if (event.isError) SubAgentController.Status.ERROR else SubAgentController.Status.DONE
                    val state = subAgentController.finalize(event.toolUseId, status)
                    if (state != null) {
                        val summaryTokens = com.adobe.clawdea.cost.TurnObservationBuilder.estimateTokens(event.content)
                        val readTokens = subagentReadTokens.remove(event.toolUseId) ?: 0
                        val inputProxy = readTokens.coerceAtLeast(summaryTokens * 5)
                        turnSubagents.add(
                            com.adobe.clawdea.cost.SubagentObservation(
                                agentType = state.agentType,
                                costUsd = 0.0,
                                summaryTokens = summaryTokens,
                                filesReadTokens = readTokens,
                                inputTokens = inputProxy,
                            ),
                        )
                        val summaryText = if (status == SubAgentController.Status.ERROR) {
                            librarianErrorText(state.agentType, event.content)
                        } else {
                            event.content
                        }
                        browserRenderer.finalizeSubAgent(
                            event.toolUseId,
                            renderer.renderSubAgentSummary(status, state.stepCount, summaryText),
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
                // Auto-collapse the generic tool block on completion, mirroring
                // sub-agent cards. No-op for edit-links, file-links, etc.
                browserRenderer.finalizeToolBlock(event.toolUseId)
                onContextLabelUpdate()
                watchForToolResultStall(progressSequence)
            }
            is CliEvent.BackgroundTask -> {
                // Lifecycle of a run_in_background sub-agent, keyed by the card id.
                // These may arrive across turn boundaries (the agent outlives the
                // turn that dispatched it); SubAgentController is panel-scoped, so
                // the card survives to receive them.
                val card = event.toolUseId
                when (event.phase) {
                    CliEvent.BackgroundTask.Phase.STARTED -> {
                        // Authoritatively mark the card background (replaces the old
                        // content/timing ack sniff). Arrives just before the launch
                        // ack tool_result, so the ack branch then keeps the card open.
                        if (subAgentController.isActive(card)) {
                            subAgentController.markLaunchedInBackground(card)
                            browserRenderer.updateSubAgentStatus(card, "&#9203; running in background")
                        }
                    }
                    CliEvent.BackgroundTask.Phase.PROGRESS -> {
                        if (subAgentController.isActive(card)) {
                            val steps = if (event.toolUses > 0) " &middot; ${event.toolUses} steps" else ""
                            val tool = event.lastToolName?.let { " &middot; ${renderer.escapeHtml(it)}" } ?: ""
                            browserRenderer.updateSubAgentStatus(card, "&#9203; running in background$steps$tool")
                        }
                    }
                    CliEvent.BackgroundTask.Phase.NOTIFICATION -> {
                        // The authoritative completion. completed → DONE with the
                        // real summary; stopped/killed → ABORTED.
                        if (subAgentController.isActive(card)) {
                            val status = if (event.isCompleted) SubAgentController.Status.DONE
                            else SubAgentController.Status.ABORTED
                            val state = subAgentController.finalize(card, status)
                            if (state != null) {
                                log.debug("subagent: background task_notification card=$card status=${event.status} → finalize $status")
                                browserRenderer.finalizeSubAgent(
                                    card,
                                    renderer.renderSubAgentSummary(status, state.stepCount, event.summary ?: ""),
                                )
                            }
                            routableToolUses.remove(card)
                            toolNameById.remove(card)
                            toolInputById.remove(card)
                            onContextLabelUpdate()
                        }
                    }
                }
            }
            is CliEvent.Result -> {
                // Emit any reasoning still buffered before the block is collapsed, so the last
                // chunk isn't dropped or rendered after finalize.
                flushReasoning()
                // Move any live reasoning out of the shared indicator before removing it.
                browserRenderer.finalizeReasoning()
                browserRenderer.hideThinkingIndicator()
                // A `/goal` loop ends with a single trailing result. On success
                // the condition was met → show "achieved". On an error result
                // (CLI crash/abort), just clear the banner without claiming success.
                if (!event.isError) {
                    goalController.onResult()?.let { achieved ->
                        browserRenderer.hideGoalBanner()
                        browserRenderer.appendHtml(renderer.renderGoalAchieved(achieved))
                    }
                } else if (goalController.current() != null) {
                    goalController.onClear()
                    browserRenderer.hideGoalBanner()
                }
                // Finalize any FOREGROUND sub-agent still active at turn end so its
                // card collapses and is released from the pinned dock — a foreground
                // card must never linger pinned after its turn ends, and one still
                // active here was interrupted (ABORTED). Background-launched agents
                // are deliberately SKIPPED: they legitimately outlive the dispatching
                // turn and close on their own `system/task_notification` (which may
                // arrive turns later). SubAgentController is panel-scoped, so the card
                // survives across the Result to receive it. Sweeping them here is the
                // exact bug that made parallel background sub-agent steps leak.
                for (id in subAgentController.activeIds()) {
                    if (subAgentController.isLaunchedInBackground(id)) continue
                    val state = subAgentController.finalize(id, SubAgentController.Status.ABORTED)
                    if (state != null) {
                        browserRenderer.finalizeSubAgent(
                            id,
                            renderer.renderSubAgentSummary(SubAgentController.Status.ABORTED, state.stepCount, ""),
                        )
                    }
                }
                if (messageBuffer.isNotEmpty()) {
                    browserRenderer.appendHtml(renderer.renderAssistantText(messageBuffer.toString()))
                    messageBuffer.clear()
                    turnHasContent = true
                }
                if (event.isError) {
                    val guidance = ErrorEnricher.enrich(event.text, bridge.backendKind)
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
                val model = currentModel.ifBlank { resolveModelLabel() }.ifBlank { null }
                val effort = resolveEffort().ifBlank { null }
                // Attribute cost to THIS TAB's provider selection, not the global effectiveProviderId.
                // The bridge's selection reflects the per-tab override or the effective default.
                val tabProviderId = bridge.selection.providerId
                val providerKey = if (tabProviderId == com.adobe.clawdea.provider.ProviderRegistry.OPENAI_COMPATIBLE_ID) {
                    // For openai-compatible, include the profile ID in the catalog key (cost is tracked
                    // per profile, not just per provider).
                    val profileId = bridge.selection.profileId.orEmpty()
                    com.adobe.clawdea.provider.ProviderRegistry.catalogKey(tabProviderId, profileId)
                } else {
                    tabProviderId
                }
                // recordTurn returns THIS turn's marginal cost. event.costUsd is the CLI's
                // per-process cumulative session total, so the footer must show the returned
                // delta (not event.costUsd) or every footer would repeat the running total.
                val turnCostUsd = costTracker.recordTurn(
                    chatId,
                    currentModel,
                    event.costUsd,
                    event.contextTokens,
                    event.inputTokens,
                    event.outputTokens,
                    event.cacheReadTokens,
                    event.cacheCreationTokens,
                    event.reasoningTokens,
                    ranUnderDefaultSelection = resolveIsDefaultModel(),
                    providerId = providerKey,
                    knowledgeBucket = pendingKnowledgeBucket,
                )
                if (turnCostUsd > 0 || totalElapsed > 0 || model != null) {
                    browserRenderer.appendHtml(
                        renderer.renderCostInfo(model, effort, turnCostUsd, totalElapsed),
                    )
                }
                pendingKnowledgeBucket = null
                val priorTurns = savingsTracker.snapshot(chatId).turnCount
                val savingsObs = com.adobe.clawdea.cost.TurnObservationBuilder.build(
                    model = currentModel,
                    remainingTurns = priorTurns,
                    subagents = turnSubagents.toList(),
                    indexTools = turnIndexToolHits.toList(),
                    primerCacheReadTokens = primerTokensFrom(event.cacheReadTokens),
                    primerCacheCreationTokens = primerTokensFrom(event.cacheCreationTokens),
                    knowledgeUpkeepUsd = 0.0,
                )
                savingsTracker.recordTurn(chatId, savingsObs)
                turnIndexToolHits.clear()
                pendingIndexToolUses.clear()
                turnSubagents.clear()
                subagentReadTokens.clear()
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

                // Give lifecycle changes first access to the now-idle boundary. A deferred backend
                // rebuild must replace this fixed bridge before edit feedback or a queued prompt can
                // start another turn on it.
                if (onTurnBecameIdle()) {
                    editReviewCoordinator.clearForNewTurn()
                    turnGenuinelyEnded = true
                    return
                }

                // Layer 2 post-turn feedback for rejected/modified edits
                var sentEditFeedback = false
                if (editReviewCoordinator.hasFeedback()) {
                    val feedback = editReviewCoordinator.buildAndClearFeedback()
                    if (feedback != null) {
                        bridge.sendMessage(feedback)
                        turnController.onUserSend()
                        onSyncStreamingUi()
                        streamStartTime = System.currentTimeMillis()
                        statusLabel.text = "${bridge.agentLabel} is thinking..."
                        watchForTurnStartStall()
                        sentEditFeedback = true
                    }
                }

                if (!sentEditFeedback) {
                    editReviewCoordinator.clearForNewTurn()
                    // Latch the genuine turn end so a stray trailing coarse event can't re-open the
                    // turn UI (the self-heal is re-armed on the next real turn — see handleEvent).
                    turnGenuinelyEnded = true
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
                        val guidance = ErrorEnricher.enrich(errorText, bridge.backendKind)
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
            is CliEvent.GoalFeedback -> {
                goalController.onFeedback(event.condition, event.reason)
                goalController.current()?.let { state ->
                    browserRenderer.updateGoalBanner(renderer.renderGoalBanner(state))
                }
                browserRenderer.appendHtml(renderer.renderGoalProgress(event.reason))
            }
        }
    }

    private fun watchForToolResultStall(toolResultProgressSequence: Long) {
        scheduleStallRecovery(TOOL_RESULT_STALL_TIMEOUT_MS, toolResultProgressSequence, onToolResultStalled)
    }

    /**
     * For a failed `wiki-librarian` subagent, prepend the WIKI-role model to the error summary so
     * the cause is diagnosable at a glance (e.g. a Bedrock Claude 3 Haiku selection that rejects
     * prompt caching with an HTTP 400). Other agents pass through unchanged.
     */
    private fun librarianErrorText(agentType: String, content: String): String {
        if (agentType != "wiki-librarian") return content
        val model = com.adobe.clawdea.provider.RoleSelectionStore(
            com.adobe.clawdea.settings.ClawDEASettings.getInstance(),
        ).get(com.adobe.clawdea.provider.AgentRole.WIKI).modelId.ifBlank { "default model" }
        return "Wiki librarian using '$model' encountered: $content"
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
        // First-byte latency from Anthropic/Bedrock can spike to several minutes when
        // the context is large (CLAUDE.md + wiki + skills + workspace + a long
        // transcript of tool results): the model must process all prior context
        // before emitting the next token. 180s was too tight on long-running
        // conversations — a legitimately-slow turn crossed it, the watchdog tore the
        // turn down and restarted the bridge mid-flight, and the indicator vanished
        // while the CLI was actually still working. 300s absorbs those slow turns
        // with a wide margin; a genuinely hung process is still recovered, just
        // later. (A false teardown is additionally self-healed in [handleEvent] when
        // the CLI resumes streaming.)
        internal const val TURN_START_STALL_TIMEOUT_MS = 300_000
        internal const val TOOL_RESULT_STALL_TIMEOUT_MS = 300_000

        // Throttle window for coalescing streamed reasoning deltas before a JCEF render.
        // ~90ms keeps the "Thinking" block feeling live (~11 renders/s) while collapsing the
        // token-rate burst that otherwise floods the EDT.
        private const val REASONING_FLUSH_MS = 90

        private const val PRIMER_CACHE_FRACTION = 0.05

        /** Absolute ceiling on per-turn primer tokens — the wiki TOC + REPO_STATE are a fixed payload. */
        private const val PRIMER_TOKENS_CEILING = 4000

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

        /**
         * True when streamed text belongs to the main agent and may be appended
         * to the shared main-bubble buffer. Only a null parent qualifies: any
         * text carrying a `parent_tool_use_id` originates from a sub-agent
         * (whether a depth-1 card we track or a deeper nested one we don't) and
         * must be rendered into that agent's own panel — never merged into the
         * main stream, which would break the main output mid-phrase when
         * sub-agents run in parallel.
         */
        internal fun isMainAgentStream(parentToolUseId: String?): Boolean =
            parentToolUseId == null

        /**
         * True when an incoming event represents live agent activity that should
         * re-assert the thinking/activity indicator. Coarse events only (a
         * message or a tool result — never per-token [CliEvent.TextDelta], to
         * avoid a JCEF round-trip per token), and only while the turn is actively
         * streaming and not paused. [CliEvent.Result] is deliberately excluded:
         * it ends the turn and hides the indicator, so poking on it would fight
         * the hide. Sub-agent activity qualifies because its inner
         * [CliEvent.AssistantMessage]/[CliEvent.ToolResult] events flow through
         * here too, keeping the hint alive for the whole delegated run.
         */
        internal fun shouldPokeIndicator(event: CliEvent, isStreaming: Boolean, isPaused: Boolean): Boolean =
            isStreaming && !isPaused && isCoarseActivityEvent(event)

        /**
         * Coarse, per-message/per-tool activity — the granularity used to re-assert
         * the indicator. Excludes per-token [CliEvent.TextDelta] (would spam a JCEF
         * round-trip per token) and [CliEvent.Result] (terminal — ends the turn).
         */
        internal fun isCoarseActivityEvent(event: CliEvent): Boolean =
            event is CliEvent.AssistantMessage || event is CliEvent.ToolResult

        /**
         * True when a coarse activity event arrives while the turn is NOT marked
         * streaming even though the bridge is still running and we are not paused —
         * i.e. the turn was torn down (usually a false-positive stall recovery whose
         * restart failed) but the CLI is demonstrably still producing output. The
         * caller re-enters streaming so the activity indicator and turn controls
         * come back.
         *
         * [turnGenuinelyEnded] hard-gates this: once a real [CliEvent.Result] has ended the turn,
         * a stray trailing coarse event must NOT re-open it. [CliEvent.Result] itself isn't a
         * coarse event, but some Claude turns emit a late duplicate/trailing message *after* the
         * result — without this gate that resurrects the activity indicator (and its pause button)
         * with no Result left to hide it (the "pause button stuck after the turn ends" symptom).
         */
        internal fun shouldRestoreStreaming(
            event: CliEvent,
            isStreaming: Boolean,
            isPaused: Boolean,
            bridgeRunning: Boolean,
            turnGenuinelyEnded: Boolean = false,
        ): Boolean =
            !turnGenuinelyEnded && !isStreaming && !isPaused && bridgeRunning && isCoarseActivityEvent(event)

        internal fun isTurnProgressEvent(event: CliEvent): Boolean =
            when (event) {
                is CliEvent.TextDelta,
                is CliEvent.ReasoningDelta,
                is CliEvent.AssistantMessage,
                is CliEvent.ToolResult,
                is CliEvent.GoalFeedback,
                is CliEvent.Result,
                is CliEvent.BackgroundTask,
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
