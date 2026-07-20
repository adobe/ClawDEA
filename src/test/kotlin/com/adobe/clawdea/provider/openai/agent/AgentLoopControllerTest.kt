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

import com.adobe.clawdea.provider.openai.tools.ToolExecutionResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AgentLoopController]: exactly-once execution + tool round limits.
 */
class AgentLoopControllerTest {

    @Test
    fun `completed tool call is never executed twice after reconnect`() = runBlocking {
        val executor = CountingToolExecutor()
        val state = ConversationState(completedToolCallIds = mutableSetOf("call-1"))

        // Reconnect scenario: the model re-sends the already-completed call-1 once (which must be
        // skipped, not re-executed), then finishes with no tool calls so the turn terminates.
        var round = 0
        val client = FakeAgentClient {
            round++
            if (round == 1) flowOf(
                AgentStreamEvent.ToolFragment(0, "call-1", "test_tool", "{}"),
                AgentStreamEvent.Finished("tool_calls"),
            ) else flowOf(
                AgentStreamEvent.Text("done"),
                AgentStreamEvent.Finished("stop"),
            )
        }

        val loop = AgentLoopController(
            client = client,
            executor = executor,
            state = state,
            maxToolRounds = 10,
            maxElapsedMs = 600_000,
            maxContextChars = 1_000_000,
        )

        val events = mutableListOf<Any>()
        loop.runTurn("continue") { event -> events.add(event) }

        assertEquals(0, executor.invocations)
    }

    @Test
    fun `loop stops after configured tool round limit`() = runBlocking {
        val executor = CountingToolExecutor()
        val state = ConversationState()

        var callCount = 0
        val client = FakeAgentClient {
            flowOf(
                AgentStreamEvent.ToolFragment(0, "call-${callCount++}", "test_tool", "{}"),
                AgentStreamEvent.Finished("tool_calls")
            )
        }

        var fired = 0
        val loop = AgentLoopController(
            client = client,
            executor = executor,
            state = state,
            maxToolRounds = 3,
            maxElapsedMs = 600_000,
            maxContextChars = 1_000_000,
            onSoftLimit = { _, count -> fired = count; false }, // stop at the first checkpoint
        )

        val result = loop.runTurn("work") { }

        assertEquals(3, fired)          // checkpoint saw toolRounds == 3
        assertFalse(result.isError)     // clean user-initiated stop
    }

    @Test
    fun `zero round limit never checkpoints and runs to natural end`() = runBlocking {
        val executor = CountingToolExecutor()
        val state = ConversationState()
        // Client returns a tool call the first 4 rounds, then no tool calls (natural end).
        var round = 0
        val client = FakeAgentClient {
            round++
            if (round <= 4) flowOf(
                AgentStreamEvent.ToolFragment(0, "call-$round", "test_tool", "{}"),
                AgentStreamEvent.Finished("tool_calls"),
            ) else flowOf(
                AgentStreamEvent.Text("done"),
                AgentStreamEvent.Finished("stop"),
            )
        }
        var checkpoints = 0
        val loop = AgentLoopController(
            client = client, executor = executor, state = state,
            maxToolRounds = 0, maxElapsedMs = 0, maxContextChars = 1_000_000,
            onSoftLimit = { _, _ -> checkpoints++; true },
        )
        val result = loop.runTurn("work") {}
        assertEquals(0, checkpoints)
        assertFalse(result.isError)
    }

    @Test
    fun `non-zero round limit checkpoints at N and 2N`() = runBlocking {
        val executor = CountingToolExecutor()
        val state = ConversationState()
        var round = 0
        val client = FakeAgentClient {
            round++
            flowOf(
                AgentStreamEvent.ToolFragment(0, "call-$round", "test_tool", "{}"),
                AgentStreamEvent.Finished("tool_calls"),
            )
        }
        val counts = mutableListOf<Int>()
        // Continue at the first checkpoint, stop at the second.
        val loop = AgentLoopController(
            client = client, executor = executor, state = state,
            maxToolRounds = 2, maxElapsedMs = 0, maxContextChars = 1_000_000,
            onSoftLimit = { _, count -> counts.add(count); counts.size < 2 },
        )
        val result = loop.runTurn("work") {}
        assertEquals(listOf(2, 2), counts) // fired at round 2, reset, fired again at 2
        assertFalse(result.isError)        // user-initiated stop is not an error
    }

    @Test
    fun `context over threshold compacts and continues`() = runBlocking {
        val executor = CountingToolExecutor()
        // Seed a large history so the char budget is exceeded on the first check.
        val big = "x".repeat(2000)
        val state = ConversationState(
            messages = mutableListOf(
                AgentMessage(role = "system", content = "sys"),
                AgentMessage(role = "user", content = big),
                AgentMessage(role = "user", content = big),
            )
        )
        var round = 0
        val client = FakeAgentClient {
            round++
            if (round == 1) flowOf(AgentStreamEvent.Text("ok"), AgentStreamEvent.Finished("stop"))
            else flowOf(AgentStreamEvent.Text("ok"), AgentStreamEvent.Finished("stop"))
        }
        var compactions = 0
        val loop = AgentLoopController(
            client = client, executor = executor, state = state,
            maxToolRounds = 0, maxElapsedMs = 0,
            maxContextChars = 3000, // char fallback budget; threshold 0.8 => compact at 2400 chars
            compactor = ConversationCompactor(summarize = { "SUMMARY" }),
            compactionThreshold = 0.8,
            onCompacted = { compactions++ },
        )
        val result = loop.runTurn("continue", appendUserMessage = false) {}
        assertEquals(1, compactions)
        assertFalse(result.isError)
    }

    @Test
    fun `context over threshold with no compactor emits fatal result`() = runBlocking {
        val state = ConversationState(
            messages = mutableListOf(AgentMessage(role = "user", content = "x".repeat(5000)))
        )
        val client = FakeAgentClient(flowOf(AgentStreamEvent.Finished("stop")))
        val events = mutableListOf<com.adobe.clawdea.cli.CliEvent>()
        val loop = AgentLoopController(
            client = client, executor = CountingToolExecutor(), state = state,
            maxToolRounds = 0, maxElapsedMs = 0, maxContextChars = 3000,
            compactor = null,
        )
        val result = loop.runTurn("continue", appendUserMessage = false) { events.add(it) }
        assertTrue(result.isError)
        assertTrue(events.filterIsInstance<com.adobe.clawdea.cli.CliEvent.Result>()
            .any { it.text.contains("Context budget exceeded") })
    }

    @Test
    fun `dangling assistant tool_calls is stripped at turn entry`() = runBlocking {
        // Seed a dangling assistant message with tool_calls from a prior ROUNDS-Stop (no following tool results).
        val state = ConversationState(
            messages = mutableListOf(
                AgentMessage(role = "user", content = "q"),
                AgentMessage(role = "assistant", toolCalls = listOf(AgentToolCall("c1", "read", "{}"))),
            )
        )
        // Client returns a single text response + Finished("stop") (no tool calls).
        val client = FakeAgentClient(flowOf(
            AgentStreamEvent.Text("answer"),
            AgentStreamEvent.Finished("stop"),
        ))
        val loop = AgentLoopController(
            client = client,
            executor = CountingToolExecutor(),
            state = state,
            maxToolRounds = 0,
            maxElapsedMs = 0,
            maxContextChars = 1_000_000,
        )
        val result = loop.runTurn("continue", appendUserMessage = true) { }
        // Assert the seeded dangling assistant message with toolCall id "c1" is gone.
        val hasDanglingC1 = state.messages.any { msg ->
            msg.role == "assistant" && msg.toolCalls.any { it.id == "c1" }
        }
        assertFalse("Dangling assistant tool_calls should be stripped", hasDanglingC1)
        assertFalse(result.isError)
    }

    @Test
    fun `context over threshold with failing summarizer emits fatal result`() = runBlocking {
        val executor = CountingToolExecutor()
        // Seed a large history so the char budget is exceeded on the first check.
        val big = "x".repeat(2000)
        val state = ConversationState(
            messages = mutableListOf(
                AgentMessage(role = "system", content = "sys"),
                AgentMessage(role = "user", content = big),
                AgentMessage(role = "user", content = big),
            )
        )
        val client = FakeAgentClient(flowOf(AgentStreamEvent.Text("ok"), AgentStreamEvent.Finished("stop")))
        val events = mutableListOf<com.adobe.clawdea.cli.CliEvent>()
        val loop = AgentLoopController(
            client = client, executor = executor, state = state,
            maxToolRounds = 0, maxElapsedMs = 0,
            maxContextChars = 3000, // char fallback budget; threshold 0.8 => compact at 2400 chars
            compactor = ConversationCompactor(summarize = { throw IllegalStateException("boom") }),
            compactionThreshold = 0.8,
        )
        val result = loop.runTurn("continue", appendUserMessage = false) { events.add(it) }
        assertTrue(result.isError)
        assertTrue(events.filterIsInstance<com.adobe.clawdea.cli.CliEvent.Result>()
            .any { it.text.contains("Context budget exceeded") })
    }
    @Test
    fun `Agent tool call routes to the subAgentRunner, not the executor`() = runBlocking {
        val executor = CountingToolExecutor()
        val runner = RecordingSubAgentRunner(reportText = "sub-agent report")

        var round = 0
        val client = FakeAgentClient {
            round++
            if (round == 1) flowOf(
                AgentStreamEvent.ToolFragment(0, "call-agent", "Agent", """{"description":"do it","prompt":"go"}"""),
                AgentStreamEvent.Finished("tool_calls"),
            ) else flowOf(
                AgentStreamEvent.Text("done"),
                AgentStreamEvent.Finished("stop"),
            )
        }

        val loop = AgentLoopController(
            client = client,
            executor = executor,
            state = ConversationState(),
            maxToolRounds = 10,
            maxElapsedMs = 600_000,
            maxContextChars = 1_000_000,
            subAgentRunner = runner,
        )

        val events = mutableListOf<com.adobe.clawdea.cli.CliEvent>()
        loop.runTurn("please dispatch") { events.add(it) }

        // Runner handled the Agent call; the synchronous executor never saw it.
        assertEquals(1, runner.calls.size)
        assertEquals("call-agent", runner.calls.single().id)
        assertEquals(0, executor.invocations)

        // The runner's report becomes the Agent tool's ToolResult (parent=null → finalizes the card).
        val agentResult = events.filterIsInstance<com.adobe.clawdea.cli.CliEvent.ToolResult>()
            .single { it.toolUseId == "call-agent" }
        assertEquals("sub-agent report", agentResult.content)
        assertFalse(agentResult.isError)
    }

    @Test
    fun `non-Agent tool calls still go to the executor even when a runner is present`() = runBlocking {
        val executor = CountingToolExecutor()
        val runner = RecordingSubAgentRunner(reportText = "unused")

        var round = 0
        val client = FakeAgentClient {
            round++
            if (round == 1) flowOf(
                AgentStreamEvent.ToolFragment(0, "call-1", "some_tool", "{}"),
                AgentStreamEvent.Finished("tool_calls"),
            ) else flowOf(
                AgentStreamEvent.Text("done"),
                AgentStreamEvent.Finished("stop"),
            )
        }

        val loop = AgentLoopController(
            client = client,
            executor = executor,
            state = ConversationState(),
            maxToolRounds = 10,
            maxElapsedMs = 600_000,
            maxContextChars = 1_000_000,
            subAgentRunner = runner,
        )

        loop.runTurn("go") { }

        assertEquals(1, executor.invocations)
        assertTrue(runner.calls.isEmpty())
    }
}

/**
 * Fake executor that counts invocations.
 */
class CountingToolExecutor : AgentToolExecutor {
    var invocations = 0

    override fun execute(toolCall: AgentToolCall): ToolExecutionResult {
        invocations++
        return ToolExecutionResult(
            toolCallId = toolCall.id,
            content = "executed",
            isError = false
        )
    }
}

/**
 * Fake sub-agent runner that records dispatches and returns a canned report.
 */
class RecordingSubAgentRunner(
    private val reportText: String,
    override val toolName: String = "Agent",
) : SubAgentRunner {
    val calls = mutableListOf<AgentToolCall>()

    override suspend fun run(
        toolCall: AgentToolCall,
        emit: (com.adobe.clawdea.cli.CliEvent) -> Unit,
    ): ToolExecutionResult {
        calls.add(toolCall)
        return ToolExecutionResult(toolCall.id, reportText, isError = false)
    }
}

/**
 * Fake client that returns a canned flow.
 */
class FakeAgentClient(
    private val flowFactory: () -> kotlinx.coroutines.flow.Flow<AgentStreamEvent>
) : AgentClient {
    constructor(flow: kotlinx.coroutines.flow.Flow<AgentStreamEvent>) : this({ flow })

    override suspend fun stream(request: AgentCompletionRequest): kotlinx.coroutines.flow.Flow<AgentStreamEvent> {
        return flowFactory()
    }
}
