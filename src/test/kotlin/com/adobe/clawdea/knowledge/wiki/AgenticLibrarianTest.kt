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
package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.provider.openai.agent.AgentClient
import com.adobe.clawdea.provider.openai.agent.AgentCompletionRequest
import com.adobe.clawdea.provider.openai.agent.AgentStreamEvent
import com.adobe.clawdea.provider.openai.agent.AgentToolCall
import com.adobe.clawdea.provider.openai.agent.AgentToolExecutor
import com.adobe.clawdea.provider.openai.tools.ToolExecutionResult
import com.adobe.clawdea.provider.openai.catalog.ModelCapability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgenticLibrarianTest {
    private val noExec = object : AgentToolExecutor {
        override fun execute(toolCall: AgentToolCall) =
            ToolExecutionResult(toolCall.id, "unused", isError = false)
    }

    /** Client that streams one assistant text chunk then completes (no tool calls). */
    private class TextClient(private val text: String) : AgentClient {
        override suspend fun stream(request: AgentCompletionRequest): Flow<AgentStreamEvent> = flow {
            emit(AgentStreamEvent.Text(text))
            emit(AgentStreamEvent.Finished(reason = "stop"))
        }
    }

    @Test fun returns_final_text_on_clean_turn() = runBlocking {
        val lib = AgenticLibrarian(
            client = TextClient("The bridge owns the process."),
            executor = noExec, tools = emptyList(), modelId = "qwen",
            systemPrompt = "you are the librarian", capability = ModelCapability.AGENTIC,
            streaming = true,
        )
        val ans = lib.ask("How does the bridge work?")
        assertFalse(ans.isError)
        assertTrue(ans.text.contains("bridge owns the process"))
    }

    @Test fun refuses_non_agentic_model_without_calling_client() = runBlocking {
        val boom = object : AgentClient {
            override suspend fun stream(request: AgentCompletionRequest): Flow<AgentStreamEvent> =
                throw AssertionError("client must not be called for non-agentic model")
        }
        val lib = AgenticLibrarian(
            client = boom, executor = noExec, tools = emptyList(), modelId = "text-only",
            systemPrompt = null, capability = ModelCapability.COMPLETION_ONLY, streaming = false,
        )
        val ans = lib.ask("q")
        assertTrue(ans.isError)
        assertTrue(ans.text.contains("not tool-capable"))
    }
}
