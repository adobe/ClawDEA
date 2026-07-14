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
package com.adobe.clawdea.chat.session

/**
 * Serializes a prior session's transcript into a plain-text block that can be prepended to the first
 * turn of a *different* backend, so a Claude conversation can continue under codex (and vice-versa)
 * even though the two CLIs have incompatible, un-cross-resumable session stores.
 *
 * This is a context hand-off, not a true resume: the new backend gets the visible conversation as
 * text (no provider-side cached state, no tool-call fidelity). Per the chosen fidelity level, only
 * user + assistant text is carried; tool calls/results are elided. When the transcript exceeds
 * [maxChars] the *oldest* turns are dropped (recency matters most for continuing) and a marker notes
 * the truncation.
 */
object TranscriptReplay {

    private const val DEFAULT_MAX_CHARS = 24_000

    /**
     * Builds the replay block, or an empty string when there's nothing to carry. [sourceLabel] is
     * the origin agent name (e.g. "Claude"/"Codex") surfaced to the target model for context.
     */
    fun serialize(
        history: List<HistoryEntry>,
        sourceLabel: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String {
        val turns = history.mapNotNull { entry ->
            when (entry) {
                is HistoryEntry.UserMessage -> "User: ${entry.text.trim()}".takeIf { entry.text.isNotBlank() }
                is HistoryEntry.AssistantText -> "Assistant: ${entry.text.trim()}".takeIf { entry.text.isNotBlank() }
                else -> null // tool_use / tool_result elided (text-only fidelity)
            }
        }
        if (turns.isEmpty()) return ""

        val (kept, truncated) = fitToBudget(turns, maxChars)
        return buildString {
            append("<prior_conversation source=\"").append(sourceLabel).append("\">\n")
            if (truncated) append("[earlier turns omitted]\n\n")
            append(kept.joinToString("\n\n"))
            append("\n</prior_conversation>")
        }
    }

    /**
     * Wraps [replayBlock] and the user's actual first message into a single prompt for the target
     * backend. Returns [userText] unchanged when there's no replay block.
     */
    fun wrapFirstMessage(replayBlock: String, userText: String): String =
        if (replayBlock.isBlank()) userText
        else "$replayBlock\n\nContinue our conversation from the transcript above. My next message:\n\n$userText"

    /** Keep the most-recent turns that fit within [maxChars]; report whether anything was dropped. */
    private fun fitToBudget(turns: List<String>, maxChars: Int): Pair<List<String>, Boolean> {
        if (maxChars <= 0) return turns to false
        val kept = ArrayDeque<String>()
        var total = 0
        for (turn in turns.asReversed()) {
            val add = turn.length + 2 // joiner
            if (total + add > maxChars && kept.isNotEmpty()) return kept.toList() to true
            kept.addFirst(turn)
            total += add
        }
        return kept.toList() to false
    }
}
