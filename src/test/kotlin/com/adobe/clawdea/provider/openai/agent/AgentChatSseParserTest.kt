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
}
