/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.chat.session

import com.adobe.clawdea.chat.MessageRenderer
import com.adobe.clawdea.chat.SubAgentController
import com.adobe.clawdea.chat.ToolMode
import com.adobe.clawdea.chat.editreview.EditReviewCoordinator

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
        for ((i, entry) in history.withIndex()) {
            if (i in consumed) continue
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
                val stepHtml = if (EditReviewCoordinator.isProposeTool(e.name) || EditReviewCoordinator.isEditTool(e.name)) {
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
