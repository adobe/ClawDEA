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
package com.adobe.clawdea.provider.openai.agent

/** Result of a compaction pass: the rebuilt history and the tool-call ids that were summarized away. */
data class CompactionResult(
    val messages: List<AgentMessage>,
    val evictedToolCallIds: Set<String>,
    val summarizedCount: Int,
)

/**
 * Pure conversation compactor. Summarizes all prior history into one message and keeps a verbatim
 * tail that begins at a clean boundary, so the OpenAI message-structure invariant (assistant
 * toolCalls immediately followed by their tool results) is never violated.
 *
 * [summarize] is injected (one completion call in production, a fake in tests). It receives the
 * span being summarized and returns prose. Exceptions propagate to the caller unchanged.
 */
class ConversationCompactor(
    private val summarize: suspend (List<AgentMessage>) -> String,
) {
    suspend fun compact(messages: List<AgentMessage>, keepTailTarget: Int): CompactionResult {
        if (messages.isEmpty()) return CompactionResult(messages, emptySet(), 0)

        // Element 0 is the system prefix (preserved verbatim). Everything else is the remainder.
        val system = messages.first()
        val remainder = messages.drop(1)

        val tailStart = cleanBoundaryAtOrAfter(remainder, (remainder.size - keepTailTarget).coerceAtLeast(0))
        val span = if (tailStart == null) remainder else remainder.subList(0, tailStart)
        val tail = if (tailStart == null) emptyList() else remainder.subList(tailStart, remainder.size)

        val summaryText = summarize(span)
        val evicted = span.flatMap { it.toolCalls }.map { it.id }.toSet()

        val rebuilt = buildList {
            add(system)
            add(AgentMessage(role = "user", content = summaryText))
            addAll(tail)
        }
        return CompactionResult(rebuilt, evicted, span.size)
    }

    /** First index >= [from] whose message is a clean boundary (user, or assistant w/o toolCalls). Null if none. */
    private fun cleanBoundaryAtOrAfter(messages: List<AgentMessage>, from: Int): Int? {
        for (i in from until messages.size) {
            val m = messages[i]
            if (m.role == "user") return i
            if (m.role == "assistant" && m.toolCalls.isEmpty()) return i
        }
        return null
    }
}
