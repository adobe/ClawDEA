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
package com.adobe.clawdea.provider.openai.catalog

import com.adobe.clawdea.provider.openai.agent.AgentClient
import com.adobe.clawdea.provider.openai.agent.AgentCompletionRequest
import com.adobe.clawdea.provider.openai.agent.AgentStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCapabilityVerifierTest {

    private fun clientReturning(vararg events: AgentStreamEvent): AgentClient = object : AgentClient {
        var lastRequest: AgentCompletionRequest? = null
        override suspend fun stream(request: AgentCompletionRequest): Flow<AgentStreamEvent> {
            lastRequest = request
            return flow { events.forEach { emit(it) } }
        }
    }

    @Test
    fun `model that calls the probe function with valid JSON args is AGENTIC`() {
        val client = clientReturning(
            AgentStreamEvent.ToolFragment(
                index = 0,
                id = "call_1",
                name = ModelCapabilityVerifier.PROBE_FUNCTION_NAME,
                arguments = """{"ok":true}""",
            ),
            AgentStreamEvent.Finished("tool_calls"),
        )
        val result = ModelCapabilityVerifier.verify(client, "model-x")
        assertEquals(ModelCapability.AGENTIC, result)
    }

    @Test
    fun `model that only returns text is COMPLETION_ONLY`() {
        val client = clientReturning(
            AgentStreamEvent.Text("I cannot call functions."),
            AgentStreamEvent.Finished("stop"),
        )
        val result = ModelCapabilityVerifier.verify(client, "model-x")
        assertEquals(ModelCapability.COMPLETION_ONLY, result)
    }

    @Test
    fun `probe call with malformed JSON args is COMPLETION_ONLY`() {
        val client = clientReturning(
            AgentStreamEvent.ToolFragment(0, "call_1", ModelCapabilityVerifier.PROBE_FUNCTION_NAME, "{not json"),
            AgentStreamEvent.Finished("tool_calls"),
        )
        val result = ModelCapabilityVerifier.verify(client, "model-x")
        assertEquals(ModelCapability.COMPLETION_ONLY, result)
    }

    @Test
    fun `probe call for a different function name is COMPLETION_ONLY`() {
        val client = clientReturning(
            AgentStreamEvent.ToolFragment(0, "call_1", "some_other_function", """{"ok":true}"""),
            AgentStreamEvent.Finished("tool_calls"),
        )
        val result = ModelCapabilityVerifier.verify(client, "model-x")
        assertEquals(ModelCapability.COMPLETION_ONLY, result)
    }

    @Test
    fun `transport failure yields UNKNOWN (never silently AGENTIC)`() {
        val client = clientReturning(
            AgentStreamEvent.Failure(status = 500, message = "server error", retryAfterSeconds = null),
        )
        val result = ModelCapabilityVerifier.verify(client, "model-x")
        assertEquals(ModelCapability.UNKNOWN, result)
    }

    @Test
    fun `request carries exactly one probe tool and no project tool calls`() {
        val capturing = ProbeCapturingClient()
        ModelCapabilityVerifier.verify(capturing, "model-x")
        assertEquals(1, capturing.captured?.tools?.size)
        assertEquals(ModelCapabilityVerifier.PROBE_FUNCTION_NAME, capturing.captured?.tools?.single()?.function?.name)
        assertEquals(emptyList<Any>(), capturing.captured?.messages?.flatMap { it.toolCalls } ?: emptyList<Any>())
    }

    @Test
    fun `probe honors the profile streaming flag`() {
        // A non-streaming gateway (streaming=false) must be probed with stream:false, exactly like a
        // real turn — otherwise the probe fails and the model is wrongly reported UNKNOWN.
        val streaming = ProbeCapturingClient()
        ModelCapabilityVerifier.verify(streaming, "model-x", stream = true)
        assertEquals(true, streaming.captured?.stream)

        val nonStreaming = ProbeCapturingClient()
        ModelCapabilityVerifier.verify(nonStreaming, "model-x", stream = false)
        assertEquals(false, nonStreaming.captured?.stream)
    }

    private class ProbeCapturingClient : AgentClient {
        var captured: AgentCompletionRequest? = null
        override suspend fun stream(request: AgentCompletionRequest): Flow<AgentStreamEvent> {
            captured = request
            return flow { emit(AgentStreamEvent.Finished("stop")) }
        }
    }
}
