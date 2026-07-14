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
package com.adobe.clawdea.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CodexEventParser]. Samples are the real `codex exec --json`
 * stdout lines captured in docs/superpowers/specs/2026-07-14-codex-interface-findings.md
 * (## Phase 2 spike closure), codex-cli 0.144.4.
 */
class CodexEventParserTest {

    private val parser = CodexEventParser(modelId = "gpt-5-codex")

    @Test
    fun `thread started maps to SystemInit with thread id as session id`() {
        val e = parser.parse("""{"type":"thread.started","thread_id":"019f608e-0d7d-77c0-89a4-986abb699e00"}""")
        assertTrue(e is CliEvent.SystemInit)
        e as CliEvent.SystemInit
        assertEquals("019f608e-0d7d-77c0-89a4-986abb699e00", e.sessionId)
        assertEquals("gpt-5-codex", e.model)
    }

    @Test
    fun `turn started is a benign lifecycle event`() {
        val e = parser.parse("""{"type":"turn.started"}""")
        assertTrue(e is CliEvent.Unknown)
    }

    @Test
    fun `agent_message item completed maps to AssistantMessage carrying the model`() {
        val e = parser.parse("""{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"ok"}}""")
        assertTrue(e is CliEvent.AssistantMessage)
        e as CliEvent.AssistantMessage
        assertEquals("ok", e.text)
        assertTrue(e.toolUses.isEmpty())
        assertEquals("gpt-5-codex", e.model)
    }

    @Test
    fun `command_execution item started maps to AssistantMessage with a shell ToolUse`() {
        val e = parser.parse(
            """{"type":"item.started","item":{"id":"item_1","type":"command_execution","command":"/bin/zsh -lc 'echo hi'","aggregated_output":"","exit_code":null,"status":"in_progress"}}"""
        )
        assertTrue(e is CliEvent.AssistantMessage)
        e as CliEvent.AssistantMessage
        assertEquals(1, e.toolUses.size)
        assertEquals("item_1", e.toolUses[0].id)
        assertTrue(e.toolUses[0].input.contains("echo hi"))
    }

    @Test
    fun `command_execution item completed maps to a successful ToolResult`() {
        val e = parser.parse(
            """{"type":"item.completed","item":{"id":"item_1","type":"command_execution","command":"/bin/zsh -lc 'echo hi'","aggregated_output":"hello-from-codex\n","exit_code":0,"status":"completed"}}"""
        )
        assertTrue(e is CliEvent.ToolResult)
        e as CliEvent.ToolResult
        assertEquals("item_1", e.toolUseId)
        assertEquals("hello-from-codex\n", e.content)
        assertFalse(e.isError)
    }

    @Test
    fun `command_execution nonzero exit maps to an error ToolResult`() {
        val e = parser.parse(
            """{"type":"item.completed","item":{"id":"item_2","type":"command_execution","command":"false","aggregated_output":"boom","exit_code":1,"status":"completed"}}"""
        )
        assertTrue(e is CliEvent.ToolResult)
        assertTrue((e as CliEvent.ToolResult).isError)
    }

    @Test
    fun `mcp_tool_call item started maps to a namespaced ToolUse`() {
        val e = parser.parse(
            """{"type":"item.started","item":{"id":"item_1","type":"mcp_tool_call","server":"clawdea","tool":"clawdea_ping","arguments":{"a":1},"result":null,"error":null,"status":"in_progress"}}"""
        )
        assertTrue(e is CliEvent.AssistantMessage)
        e as CliEvent.AssistantMessage
        assertEquals(1, e.toolUses.size)
        assertEquals("mcp__clawdea__clawdea_ping", e.toolUses[0].name)
        assertTrue(e.toolUses[0].input.contains("\"a\""))
    }

    @Test
    fun `mcp_tool_call completed success extracts the text content`() {
        val e = parser.parse(
            """{"type":"item.completed","item":{"id":"item_1","type":"mcp_tool_call","server":"clawdea","tool":"clawdea_ping","arguments":{},"result":{"content":[{"type":"text","text":"pong-42"}],"structured_content":null},"error":null,"status":"completed"}}"""
        )
        assertTrue(e is CliEvent.ToolResult)
        e as CliEvent.ToolResult
        assertEquals("pong-42", e.content)
        assertFalse(e.isError)
    }

    @Test
    fun `mcp_tool_call failed maps to an error ToolResult from the error message`() {
        val e = parser.parse(
            """{"type":"item.completed","item":{"id":"item_1","type":"mcp_tool_call","server":"clawdea","tool":"x","arguments":{},"result":null,"error":{"message":"user cancelled MCP tool call"},"status":"failed"}}"""
        )
        assertTrue(e is CliEvent.ToolResult)
        e as CliEvent.ToolResult
        assertTrue(e.isError)
        assertTrue(e.content.contains("user cancelled"))
    }

    @Test
    fun `turn completed maps usage to a Result token breakdown`() {
        val e = parser.parse(
            """{"type":"turn.completed","usage":{"input_tokens":24270,"cached_input_tokens":19968,"output_tokens":104,"reasoning_output_tokens":6}}"""
        )
        assertTrue(e is CliEvent.Result)
        e as CliEvent.Result
        assertFalse(e.isError)
        assertEquals(24270, e.inputTokens)
        assertEquals(19968, e.cacheReadTokens)
        // reasoning output tokens are billed as output and folded into outputTokens.
        assertEquals(110, e.outputTokens)
    }

    @Test
    fun `turn failed with 401 maps to AuthFailure`() {
        val e = parser.parse(
            """{"type":"turn.failed","error":{"message":"unexpected status 401 Unauthorized: Missing bearer or basic authentication in header"}}"""
        )
        assertTrue(e is CliEvent.AuthFailure)
    }

    @Test
    fun `turn failed with a generic error maps to an error Result`() {
        val e = parser.parse("""{"type":"turn.failed","error":{"message":"model produced an invalid response"}}""")
        assertTrue(e is CliEvent.Result)
        assertTrue((e as CliEvent.Result).isError)
    }

    @Test
    fun `top-level 401 error maps to AuthFailure`() {
        val e = parser.parse(
            """{"type":"error","message":"unexpected status 401 Unauthorized: Missing bearer or basic authentication in header, url: https://api.openai.com/v1/responses"}"""
        )
        assertTrue(e is CliEvent.AuthFailure)
    }

    @Test
    fun `transient reconnect error is not surfaced as an auth failure`() {
        val e = parser.parse("""{"type":"error","message":"Reconnecting... 2/5"}""")
        assertTrue(e is CliEvent.Unknown)
    }

    @Test
    fun `garbage line is Unknown rather than throwing`() {
        val e = parser.parse("not json at all")
        assertTrue(e is CliEvent.Unknown)
    }
}
