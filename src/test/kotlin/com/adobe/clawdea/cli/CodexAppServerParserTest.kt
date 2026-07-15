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
 * Unit tests for [CodexAppServerParser]: maps the `codex app-server` JSON-RPC notification stream
 * to ClawDEA's normalized [CliEvent]s.
 */
class CodexAppServerParserTest {

    private fun parser(model: String = "gpt-5.6-sol") = CodexAppServerParser(model)

    @Test
    fun `thread started maps to SystemInit with session id and injected model`() {
        val e = parser().parse("""{"method":"thread/started","params":{"thread":{"id":"T1"}}}""")
        assertTrue(e is CliEvent.SystemInit)
        e as CliEvent.SystemInit
        assertEquals("T1", e.sessionId)
        assertEquals("gpt-5.6-sol", e.model)
    }

    @Test
    fun `agent message delta maps to TextDelta`() {
        val e = parser().parse("""{"method":"item/agentMessage/delta","params":{"delta":"Hello","itemId":"i1","threadId":"T1","turnId":"u1"}}""")
        assertTrue(e is CliEvent.TextDelta)
        assertEquals("Hello", (e as CliEvent.TextDelta).text)
    }

    @Test
    fun `empty agent message delta is ignored`() {
        val e = parser().parse("""{"method":"item/agentMessage/delta","params":{"delta":""}}""")
        assertTrue(e is CliEvent.Unknown)
        assertEquals("", (e as CliEvent.Unknown).rawJson)
    }

    @Test
    fun `reasoning text delta maps to ReasoningDelta`() {
        val e = parser().parse("""{"method":"item/reasoning/textDelta","params":{"delta":"Let me think"}}""")
        assertTrue(e is CliEvent.ReasoningDelta)
        e as CliEvent.ReasoningDelta
        assertEquals("Let me think", e.text)
        assertFalse(e.summary)
    }

    @Test
    fun `reasoning summary delta maps to ReasoningDelta with summary flag`() {
        val e = parser().parse("""{"method":"item/reasoning/summaryTextDelta","params":{"delta":"Summary"}}""")
        assertTrue(e is CliEvent.ReasoningDelta)
        e as CliEvent.ReasoningDelta
        assertEquals("Summary", e.text)
        assertTrue(e.summary)
    }

    @Test
    fun `empty reasoning delta is ignored`() {
        val e = parser().parse("""{"method":"item/reasoning/textDelta","params":{"delta":""}}""")
        assertTrue(e is CliEvent.Unknown)
        assertEquals("", (e as CliEvent.Unknown).rawJson)
    }

    @Test
    fun `completed agent message maps to AssistantMessage with text and model`() {
        val e = parser().parse("""{"method":"item/completed","params":{"item":{"type":"agentMessage","id":"i1","text":"Final answer"}}}""")
        assertTrue(e is CliEvent.AssistantMessage)
        e as CliEvent.AssistantMessage
        assertEquals("Final answer", e.text)
        assertEquals("gpt-5.6-sol", e.model)
        assertTrue(e.toolUses.isEmpty())
    }

    @Test
    fun `started command execution maps to a Bash tool use`() {
        val e = parser().parse("""{"method":"item/started","params":{"item":{"type":"commandExecution","id":"c1","command":"echo hi"}}}""")
        assertTrue(e is CliEvent.AssistantMessage)
        e as CliEvent.AssistantMessage
        assertEquals(1, e.toolUses.size)
        val tu = e.toolUses[0]
        assertEquals("c1", tu.id)
        assertEquals("Bash", tu.name)
        assertTrue(tu.input.contains("echo hi"))
    }

    @Test
    fun `completed failed command execution maps to an error ToolResult`() {
        val e = parser().parse("""{"method":"item/completed","params":{"item":{"type":"commandExecution","id":"c1","aggregatedOutput":"boom","exitCode":1,"status":"completed"}}}""")
        assertTrue(e is CliEvent.ToolResult)
        e as CliEvent.ToolResult
        assertEquals("c1", e.toolUseId)
        assertEquals("boom", e.content)
        assertTrue(e.isError)
    }

    @Test
    fun `completed successful command execution is not an error`() {
        val e = parser().parse("""{"method":"item/completed","params":{"item":{"type":"commandExecution","id":"c1","aggregatedOutput":"ok","exitCode":0,"status":"completed"}}}""")
        assertTrue(e is CliEvent.ToolResult)
        assertFalse((e as CliEvent.ToolResult).isError)
    }

    @Test
    fun `started mcp tool call maps to a namespaced tool use`() {
        val e = parser().parse("""{"method":"item/started","params":{"item":{"type":"mcpToolCall","id":"m1","server":"clawdea","tool":"find_files","arguments":{"q":"x"}}}}""")
        assertTrue(e is CliEvent.AssistantMessage)
        val tu = (e as CliEvent.AssistantMessage).toolUses.single()
        assertEquals("mcp__clawdea__find_files", tu.name)
        assertTrue(tu.input.contains("\"q\""))
    }

    @Test
    fun `completed mcp tool call extracts text content`() {
        val e = parser().parse("""{"method":"item/completed","params":{"item":{"type":"mcpToolCall","id":"m1","status":"completed","result":{"content":[{"type":"text","text":"result body"}]}}}}""")
        assertTrue(e is CliEvent.ToolResult)
        e as CliEvent.ToolResult
        assertEquals("result body", e.content)
        assertFalse(e.isError)
    }

    @Test
    fun `mcp tool call error maps to error ToolResult`() {
        val e = parser().parse("""{"method":"item/completed","params":{"item":{"type":"mcpToolCall","id":"m1","status":"failed","error":{"message":"nope"}}}}""")
        assertTrue(e is CliEvent.ToolResult)
        e as CliEvent.ToolResult
        assertEquals("nope", e.content)
        assertTrue(e.isError)
    }

    @Test
    fun `token usage then turn completed yields Result with per-turn tokens`() {
        val p = parser()
        val usage = p.parse(
            """{"method":"thread/tokenUsage/updated","params":{"threadId":"T1","turnId":"u1","tokenUsage":{"last":{"inputTokens":100,"cachedInputTokens":20,"outputTokens":50,"reasoningOutputTokens":10,"totalTokens":180},"total":{"totalTokens":500},"modelContextWindow":272000}}}""",
        )
        assertTrue(usage is CliEvent.Unknown) // stashed, not surfaced

        val e = p.parse("""{"method":"turn/completed","params":{"threadId":"T1","turn":{"id":"u1","status":"completed"}}}""")
        assertTrue(e is CliEvent.Result)
        e as CliEvent.Result
        assertFalse(e.isError)
        assertEquals(100, e.inputTokens)
        assertEquals(20, e.cacheReadTokens)
        assertEquals(60, e.outputTokens) // output + reasoning
        assertEquals(500, e.contextTokens)
        assertEquals(272000, e.contextWindow)
    }

    @Test
    fun `turn completed without prior usage yields zeroed Result`() {
        val e = parser().parse("""{"method":"turn/completed","params":{"turn":{"id":"u1","status":"completed"}}}""")
        assertTrue(e is CliEvent.Result)
        e as CliEvent.Result
        assertEquals(0, e.inputTokens)
        assertEquals(0, e.contextTokens)
    }

    @Test
    fun `failed turn maps to an error Result carrying the message`() {
        val e = parser().parse("""{"method":"turn/completed","params":{"turn":{"id":"u1","status":"failed","error":{"message":"model exploded"}}}}""")
        assertTrue(e is CliEvent.Result)
        e as CliEvent.Result
        assertTrue(e.isError)
        assertEquals("model exploded", e.text)
    }

    @Test
    fun `auth-flavored error maps to AuthFailure`() {
        val e = parser().parse("""{"method":"error","params":{"message":"401 Unauthorized: please sign in"}}""")
        assertTrue(e is CliEvent.AuthFailure)
    }

    @Test
    fun `generic error maps to an error Result`() {
        val e = parser().parse("""{"method":"error","params":{"message":"rate limited"}}""")
        assertTrue(e is CliEvent.Result)
        assertTrue((e as CliEvent.Result).isError)
    }

    @Test
    fun `unhandled notification is ignored with blank rawJson`() {
        val e = parser().parse("""{"method":"turn/planUpdated","params":{"plan":[]}}""")
        assertTrue(e is CliEvent.Unknown)
        assertEquals("", (e as CliEvent.Unknown).rawJson)
    }

    @Test
    fun `malformed line is ignored with blank rawJson`() {
        val e = parser().parse("not json")
        assertTrue(e is CliEvent.Unknown)
        assertEquals("", (e as CliEvent.Unknown).rawJson)
    }
}
