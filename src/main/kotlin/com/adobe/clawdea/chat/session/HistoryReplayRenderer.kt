/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.chat.session

import com.adobe.clawdea.chat.MessageRenderer
import com.adobe.clawdea.chat.SubAgentController
import com.adobe.clawdea.chat.TaskWidgetController
import com.adobe.clawdea.chat.ToolMode
import com.adobe.clawdea.chat.editreview.EditReviewCoordinator
import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.cli.TaskEventExtractor

/**
 * Pure translation of parsed [HistoryEntry] list into the HTML fragments the
 * chat view replays. Sub-agent (`Agent`) dispatches and their inner tool calls
 * are reconstructed as collapsed cards matching the finalized live rendering.
 * Extracted from SessionManager so it can be unit-tested with a real
 * [MessageRenderer] (no IntelliJ Project required).
 */
object HistoryReplayRenderer {

    fun render(history: List<HistoryEntry>, renderer: MessageRenderer): List<String> {
        val out = mutableListOf<String>()
        val consumed = mutableSetOf<Int>()  // child tool_use indices folded into a card

        // Reconstruct the todo widget from top-level TaskCreate/TaskUpdate calls,
        // so a resumed session shows the final checklist once instead of a row of
        // empty per-call badges. Mirrors the live model (a single widget that
        // updates in place); we emit the final state at the first task-tool spot.
        val taskToolIndices = mutableSetOf<Int>()
        val taskWidgetHtml = buildTaskWidget(history, taskToolIndices)
        val firstTaskToolIdx = taskToolIndices.minOrNull() ?: -1

        for ((i, entry) in history.withIndex()) {
            if (i in consumed) continue
            if (i in taskToolIndices) {
                // Suppress the individual badge; emit the reconstructed widget
                // once, at the position the live widget would have first appeared.
                if (i == firstTaskToolIdx && taskWidgetHtml.isNotBlank()) out.add(taskWidgetHtml)
                continue
            }
            when (entry) {
                is HistoryEntry.UserMessage -> out.add(renderer.renderUserMessage(entry.text))
                is HistoryEntry.AssistantText -> out.add(renderer.renderAssistantText(entry.text))
                is HistoryEntry.ToolUse -> {
                    if (SubAgentController.isSubAgentTool(entry.name)) {
                        out.add(renderSubAgentCard(history, i, entry, renderer, consumed))
                    } else {
                        val (resultContent, resultIsError, _) = findToolResult(history, i, entry.id)
                        val html = renderer.renderToolUseFromHistory(
                            toolName = entry.name,
                            input = entry.input,
                            toolUseId = entry.id,
                            resultContent = resultContent,
                            isError = resultIsError,
                        )
                        if (html.isNotBlank()) out.add(html)
                    }
                }
                is HistoryEntry.ToolResult -> {
                    // No-op: every tool_result is inlined under its tool block
                    // (findToolResult lookahead) or folded into a sub-agent card.
                    // Because this branch never emits, child/agent results don't
                    // need to be tracked in `consumed` — only child tool_uses do.
                }
            }
        }
        return out
    }

    /**
     * Replay the top-level task-widget tool calls (TaskCreate/TaskUpdate, etc.)
     * into a [TaskWidgetController] and return the final widget HTML (or "" when
     * there were none). Records the index of every such top-level entry in
     * [taskToolIndices] so the caller can suppress the per-call badges. Sub-agent
     * inner task tools (non-null parent) are left to the sub-agent card grouping.
     */
    private fun buildTaskWidget(history: List<HistoryEntry>, taskToolIndices: MutableSet<Int>): String {
        val widget = TaskWidgetController()
        val extractor = TaskEventExtractor()
        for ((i, entry) in history.withIndex()) {
            if (entry !is HistoryEntry.ToolUse) continue
            if (entry.parentToolUseId != null) continue
            if (entry.name !in MessageRenderer.TASK_TOOLS) continue
            taskToolIndices.add(i)
            val (resultContent, resultIsError, _) = findToolResult(history, i, entry.id)
            val event = extractor.extract(
                CliEvent.ToolUse(entry.id, entry.name, entry.input),
                CliEvent.ToolResult(entry.id, resultContent ?: "", resultIsError),
            )
            if (event != null) widget.onTaskEvent(event)
        }
        return widget.renderWidget()
    }

    private fun renderSubAgentCard(
        history: List<HistoryEntry>,
        agentIdx: Int,
        agent: HistoryEntry.ToolUse,
        renderer: MessageRenderer,
        consumed: MutableSet<Int>,
    ): String {
        val agentType = MessageRenderer.extractJsonString(agent.input, "subagent_type") ?: "agent"
        val description = MessageRenderer.extractJsonString(agent.input, "description") ?: ""

        val childrenHtml = StringBuilder()
        var stepCount = 0
        for (j in agentIdx + 1 until history.size) {
            val e = history[j]
            if (e is HistoryEntry.ToolUse && e.parentToolUseId == agent.id) {
                consumed.add(j)  // only child tool_uses need skipping; results are no-ops in render()
                stepCount++
                val (childResult, childIsError, _) = findToolResult(history, j, e.id)
                val isReadWithPath = e.name == "Read" &&
                    MessageRenderer.extractJsonString(e.input, "file_path") != null
                val stepHtml = if (EditReviewCoordinator.isProposeTool(e.name) || EditReviewCoordinator.isEditTool(e.name) || isReadWithPath) {
                    renderer.renderToolUseEvent(e.name, e.input, e.id, ToolMode.Replay(childResult, childIsError))
                } else {
                    renderer.renderInnerToolUse(e.name, e.input, e.id, childResult)
                }
                childrenHtml.append(stepHtml)
            }
        }

        val (agentResult, agentIsError, agentResultIdx) = findToolResult(history, agentIdx, agent.id)
        val status = when {
            agentResultIdx == -1 -> SubAgentController.Status.ABORTED
            agentIsError -> SubAgentController.Status.ERROR
            else -> SubAgentController.Status.DONE
        }
        return renderer.renderSubAgentCardFromHistory(
            agentType = agentType,
            description = description,
            toolUseId = agent.id,
            status = status,
            stepCount = stepCount,
            resultText = agentResult ?: "",
            childrenHtml = childrenHtml.toString(),
        )
    }

    private fun findToolResult(
        history: List<HistoryEntry>,
        startIdx: Int,
        toolUseId: String,
    ): Triple<String?, Boolean, Int> {
        if (toolUseId.isBlank()) return Triple(null, false, -1)
        for (j in startIdx + 1 until history.size) {
            val candidate = history[j]
            if (candidate is HistoryEntry.ToolResult && candidate.toolUseId == toolUseId) {
                return Triple(candidate.content, candidate.isError, j)
            }
        }
        return Triple(null, false, -1)
    }
}
