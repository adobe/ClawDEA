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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatSseParserTest {
    @Test
    fun `parser emits text reasoning tool fragments usage and finish`() {
        val parser = AgentChatSseParser()
        assertEquals(
            AgentStreamEvent.Text("hi"),
            parser.parse("""data: {"choices":[{"delta":{"content":"hi"}}]}"""),
        )
        assertEquals(
            AgentStreamEvent.Reasoning("consider"),
            parser.parse("""data: {"choices":[{"delta":{"reasoning_content":"consider"}}]}"""),
        )
        assertEquals(
            AgentStreamEvent.ToolFragment(0, "call-1", "find_files", """{"pat"""),
            parser.parse("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"find_files","arguments":"{\"pat"}}]}}]}"""),
        )
    }

    @Test
    fun `parser handles usage with cached and reasoning tokens`() {
        val parser = AgentChatSseParser()
        val result = parser.parse(
            """data: {"usage":{"prompt_tokens":100,"completion_tokens":50,"prompt_tokens_details":{"cached_tokens":20},"completion_tokens_details":{"reasoning_tokens":10}}}"""
        )
        assertEquals(
            AgentStreamEvent.Usage(
                inputTokens = 100,
                outputTokens = 50,
                cachedInputTokens = 20,
                reasoningTokens = 10
            ),
            result
        )
    }

    @Test
    fun `parser handles finish reason`() {
        val parser = AgentChatSseParser()
        val result = parser.parse("""data: {"choices":[{"finish_reason":"stop"}]}""")
        assertEquals(AgentStreamEvent.Finished("stop"), result)
    }

    @Test
    fun `parser keeps trailing content when finish_reason arrives in the same chunk`() {
        // OpenAI/vLLM final chunk can carry BOTH a last delta.content AND finish_reason. The
        // content must not be dropped in favour of the Finished signal.
        val parser = AgentChatSseParser()
        val result = parser.parse(
            """data: {"choices":[{"delta":{"content":"bye"},"finish_reason":"stop"}]}"""
        )
        assertEquals(AgentStreamEvent.Text("bye"), result)
    }

    @Test
    fun `parser keeps trailing reasoning when finish_reason arrives in the same chunk`() {
        val parser = AgentChatSseParser()
        val result = parser.parse(
            """data: {"choices":[{"delta":{"reasoning_content":"hmm"},"finish_reason":"stop"}]}"""
        )
        assertEquals(AgentStreamEvent.Reasoning("hmm"), result)
    }

    @Test
    fun `parser surfaces finish_reason for empty delta chunk`() {
        // The common terminal shape is an empty delta object plus finish_reason: nothing to emit
        // from the delta, so Finished is surfaced.
        val parser = AgentChatSseParser()
        assertEquals(
            AgentStreamEvent.Finished("stop"),
            parser.parse("""data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""),
        )
        assertEquals(
            AgentStreamEvent.Finished("stop"),
            parser.parse("""data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}"""),
        )
    }

    @Test
    fun `parser handles top-level error`() {
        val parser = AgentChatSseParser()
        val result = parser.parse("""data: {"error":{"message":"Rate limit exceeded"}}""")
        assertEquals(
            AgentStreamEvent.Failure(null, "Rate limit exceeded", null),
            result
        )
    }

    @Test
    fun `parser returns null for DONE keepalive`() {
        val parser = AgentChatSseParser()
        assertNull(parser.parse("data: [DONE]"))
    }

    @Test
    fun `parser returns null for blank lines and comments`() {
        val parser = AgentChatSseParser()
        assertNull(parser.parse(""))
        assertNull(parser.parse(": keepalive"))
    }

    @Test
    fun `parser handles tool fragment with missing arguments`() {
        val parser = AgentChatSseParser()
        val result = parser.parse(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call-2","function":{"name":"read"}}]}}]}"""
        )
        assertEquals(
            AgentStreamEvent.ToolFragment(1, "call-2", "read", ""),
            result
        )
    }

    @Test
    fun `parser handles continuation tool fragment`() {
        val parser = AgentChatSseParser()
        val result = parser.parse(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"pattern"}}]}}]}"""
        )
        assertEquals(
            AgentStreamEvent.ToolFragment(0, null, null, "pattern"),
            result
        )
    }

    // --- Non-streamed (single JSON chat.completion) parsing ---

    @Test
    fun `non-streamed content-only completion yields Text Usage Finished`() {
        val parser = AgentChatSseParser()
        val events = parser.parseNonStreamedCompletion(
            """{"object":"chat.completion","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hello world"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":2},"completion_tokens_details":{"reasoning_tokens":1}}}"""
        )
        assertEquals(
            listOf(
                AgentStreamEvent.Text("hello world"),
                AgentStreamEvent.Usage(10, 5, 2, 1),
                AgentStreamEvent.Finished("stop"),
            ),
            events,
        )
    }

    @Test
    fun `non-streamed reasoning plus content yields Reasoning then Text`() {
        val parser = AgentChatSseParser()
        val events = parser.parseNonStreamedCompletion(
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","reasoning_content":"thinking","content":"answer"}}]}"""
        )
        assertEquals(
            listOf(
                AgentStreamEvent.Reasoning("thinking"),
                AgentStreamEvent.Text("answer"),
                AgentStreamEvent.Finished("stop"),
            ),
            events,
        )
    }

    @Test
    fun `non-streamed tool_calls yield one complete ToolFragment per call then Finished`() {
        val parser = AgentChatSseParser()
        val events = parser.parseNonStreamedCompletion(
            """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"find_files","arguments":"{\"q\":\"a\"}"}}]}}]}"""
        )
        assertEquals(
            listOf(
                AgentStreamEvent.ToolFragment(0, "call_1", "find_files", """{"q":"a"}"""),
                AgentStreamEvent.Finished("tool_calls"),
            ),
            events,
        )
    }

    @Test
    fun `non-streamed missing finish_reason defaults to stop`() {
        val parser = AgentChatSseParser()
        val events = parser.parseNonStreamedCompletion(
            """{"choices":[{"message":{"role":"assistant","content":"hi"}}]}"""
        )
        assertEquals(
            listOf(AgentStreamEvent.Text("hi"), AgentStreamEvent.Finished("stop")),
            events,
        )
    }

    @Test
    fun `non-streamed top-level error yields a single Failure`() {
        val parser = AgentChatSseParser()
        val events = parser.parseNonStreamedCompletion(
            """{"error":{"message":"Bad request"}}"""
        )
        assertEquals(listOf(AgentStreamEvent.Failure(null, "Bad request", null)), events)
    }

    @Test
    fun `non-streamed malformed body yields no events`() {
        val parser = AgentChatSseParser()
        assertEquals(emptyList<AgentStreamEvent>(), parser.parseNonStreamedCompletion("not json"))
    }

    @Test
    fun `non-streamed empty choices surfaces a failure instead of silence`() {
        val parser = AgentChatSseParser()
        // A 200 body with no choices and no error must not yield nothing (that showed the user an
        // empty answer). Surface a failure so the turn ends with a visible error.
        val events = parser.parseNonStreamedCompletion("""{"choices":[]}""")
        assertEquals(1, events.size)
        assertTrue(events[0] is AgentStreamEvent.Failure)
    }

    @Test
    fun `non-streamed string error is surfaced (not dropped)`() {
        val parser = AgentChatSseParser()
        // The EHL/APC gateway returns HTTP 200 with a STRING error plus a large details blob:
        // {"error":"<msg>","details":"<stack trace>"}. `error` is a string here, not an object.
        val events = parser.parseNonStreamedCompletion(
            """{"error":"Internal server error","details":"Traceback (most recent call last): ...very long..."}"""
        )
        assertEquals(1, events.size)
        val failure = events[0] as AgentStreamEvent.Failure
        assertTrue("must carry the error string", failure.message.contains("Internal server error"))
        assertTrue("must include the detail cause", failure.message.contains("Traceback"))
        assertTrue("detail must be bounded", failure.message.length < 400)
    }

    @Test
    fun `sse string error is surfaced`() {
        val parser = AgentChatSseParser()
        val event = parser.parse("""data: {"error":"boom"}""")
        assertEquals(AgentStreamEvent.Failure(null, "boom", null), event)
    }
}
