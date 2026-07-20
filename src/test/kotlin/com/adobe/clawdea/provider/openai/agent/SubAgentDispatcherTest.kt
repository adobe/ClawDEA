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

import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.provider.openai.tools.ToolExecutionResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentDispatcherTest {

    private fun dispatcher(client: AgentClient, executor: AgentToolExecutor = CountingToolExecutor()) =
        SubAgentDispatcher(
            client = client,
            executor = executor,
            tools = emptyList(),
            modelId = "test-model",
            systemPrompt = "you are a sub-agent",
            streaming = true,
            maxToolRounds = 10,
            maxElapsedMs = 600_000,
        )

    private fun agentCall(argumentsJson: String) =
        AgentToolCall(id = "call-agent", name = "Agent", argumentsJson = argumentsJson)

    @Test
    fun `runs the nested turn and returns its final text`() = runBlocking {
        val client = FakeAgentClient(flowOf(AgentStreamEvent.Text("nested done"), AgentStreamEvent.Finished("stop")))
        val result = dispatcher(client).run(agentCall("""{"description":"d","prompt":"do the thing"}""")) {}

        assertFalse(result.isError)
        assertEquals("nested done", result.content)
        assertEquals("call-agent", result.toolCallId)
    }

    @Test
    fun `child events are re-tagged with the dispatching tool_use id`() = runBlocking {
        // Nested turn: one tool round then a final answer, so it emits an AssistantMessage +
        // ToolResult + a terminal Result.
        var round = 0
        val client = FakeAgentClient {
            round++
            if (round == 1) flowOf(
                AgentStreamEvent.ToolFragment(0, "inner-1", "some_tool", "{}"),
                AgentStreamEvent.Finished("tool_calls"),
            ) else flowOf(
                AgentStreamEvent.Text("nested answer"),
                AgentStreamEvent.Finished("stop"),
            )
        }

        val childEvents = mutableListOf<CliEvent>()
        val result = dispatcher(client).run(agentCall("""{"description":"d","prompt":"go"}""")) { childEvents.add(it) }

        assertFalse(result.isError)
        // Every forwarded child event carries the parent id...
        val assistant = childEvents.filterIsInstance<CliEvent.AssistantMessage>()
        assertTrue("expected at least one child AssistantMessage", assistant.isNotEmpty())
        assertTrue(assistant.all { it.parentToolUseId == "call-agent" })
        val innerToolResults = childEvents.filterIsInstance<CliEvent.ToolResult>()
        assertTrue(innerToolResults.all { it.parentToolUseId == "call-agent" })
        // ...and the nested loop's OWN terminal Result is swallowed (the parent loop emits the
        // card-finalizing ToolResult from the returned value instead).
        assertTrue("nested Result must not leak", childEvents.none { it is CliEvent.Result })
    }

    @Test
    fun `missing prompt returns a soft error without running the loop`() = runBlocking {
        var streamed = false
        val client = FakeAgentClient {
            streamed = true
            flowOf(AgentStreamEvent.Text("should not run"), AgentStreamEvent.Finished("stop"))
        }
        val result = dispatcher(client).run(agentCall("""{"description":"d"}""")) {}

        assertTrue(result.isError)
        assertTrue(result.content.contains("missing required parameter: prompt"))
        assertFalse("loop must not run when prompt is missing", streamed)
    }

    @Test
    fun `malformed arguments return a soft error`() = runBlocking {
        val client = FakeAgentClient(flowOf(AgentStreamEvent.Finished("stop")))
        val result = dispatcher(client).run(agentCall("not-json")) {}

        assertTrue(result.isError)
        assertTrue(result.content.contains("Malformed Agent arguments"))
    }

    @Test
    fun `nested terminal error surfaces as an error result`() = runBlocking {
        val client = FakeAgentClient(flowOf(AgentStreamEvent.Failure(null, "upstream 500", null)))
        val result = dispatcher(client).run(agentCall("""{"description":"d","prompt":"go"}""")) {}

        assertTrue(result.isError)
    }
}
