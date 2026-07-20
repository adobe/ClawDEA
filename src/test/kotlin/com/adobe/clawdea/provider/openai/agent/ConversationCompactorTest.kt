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

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompactorTest {

    private fun sys() = AgentMessage(role = "system", content = "system prompt")
    private fun user(t: String) = AgentMessage(role = "user", content = t)
    private fun asstTool(id: String) =
        AgentMessage(role = "assistant", toolCalls = listOf(AgentToolCall(id, "read", "{}")))
    private fun toolRes(id: String) = AgentMessage(role = "tool", content = "result", toolCallId = id)

    private val compactor = ConversationCompactor(summarize = { "SUMMARY" })

    @Test
    fun `keeps system prefix and appends summary before tail`() = runBlocking {
        val msgs = listOf(sys(), user("goal"), asstTool("c1"), toolRes("c1"), user("next question"))
        val result = compactor.compact(msgs, keepTailTarget = 1)

        assertEquals("system", result.messages.first().role)
        assertEquals("SUMMARY", result.messages[1].content)
        assertEquals("user", result.messages[1].role)
        assertEquals("next question", result.messages.last().content)
    }

    @Test
    fun `never starts tail between assistant toolCalls and its tool result`() = runBlocking {
        // keepTailTarget=1 would naively start at the last message (the tool result), orphaning it.
        // The compactor must walk forward to the next clean boundary instead.
        val msgs = listOf(sys(), user("goal"), asstTool("c1"), toolRes("c1"))
        val result = compactor.compact(msgs, keepTailTarget = 1)

        // No tail message may be a bare tool result whose assistant parent was summarized away.
        val tail = result.messages.drop(2) // after [system, summary]
        assertFalse(tail.any { it.role == "tool" && it.toolCallId == "c1" })
    }

    @Test
    fun `evicts summarized tool call ids`() = runBlocking {
        val msgs = listOf(sys(), user("goal"), asstTool("c1"), toolRes("c1"), user("q2"))
        val result = compactor.compact(msgs, keepTailTarget = 1)
        assertTrue(result.evictedToolCallIds.contains("c1"))
    }

    @Test
    fun `empty tail summarizes everything when no clean boundary in window`() = runBlocking {
        // Only message after system is a tool result / assistant-with-tools: no clean tail boundary.
        val msgs = listOf(sys(), asstTool("c1"), toolRes("c1"))
        val result = compactor.compact(msgs, keepTailTarget = 1)
        assertEquals("system", result.messages[0].role)
        assertEquals("SUMMARY", result.messages[1].content)
        assertEquals(2, result.messages.size) // system + summary, no tail
        assertTrue(result.evictedToolCallIds.contains("c1"))
    }

    @Test
    fun `summarize failure propagates`() = runBlocking {
        val failing = ConversationCompactor(summarize = { throw IllegalStateException("boom") })
        var threw = false
        try {
            failing.compact(listOf(sys(), user("a"), user("b")), keepTailTarget = 1)
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }
}
