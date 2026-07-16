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

        val client = FakeAgentClient(
            flowOf(
                AgentStreamEvent.ToolFragment(0, "call-1", "test_tool", "{}"),
                AgentStreamEvent.Finished("tool_calls")
            )
        )

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

        val loop = AgentLoopController(
            client = client,
            executor = executor,
            state = state,
            maxToolRounds = 3,
            maxElapsedMs = 600_000,
            maxContextChars = 1_000_000,
        )

        val events = mutableListOf<Any>()
        val result = loop.runTurn("work") { event -> events.add(event) }

        assertTrue(result.isError)
        assertEquals(3, result.toolRounds)
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
