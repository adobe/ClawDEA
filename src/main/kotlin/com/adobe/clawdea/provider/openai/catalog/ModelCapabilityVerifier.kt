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
import com.adobe.clawdea.provider.openai.agent.AgentMessage
import com.adobe.clawdea.provider.openai.agent.AgentStreamEvent
import com.adobe.clawdea.provider.openai.agent.OpenAiFunctionDefinition
import com.adobe.clawdea.provider.openai.agent.OpenAiToolDefinition
import com.adobe.clawdea.provider.openai.agent.ToolCallAssembler
import com.adobe.clawdea.provider.openai.client.OpenAiCompatibleClient
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * Explicit, user-initiated capability check for a candidate model. Sends ONE completion request
 * carrying a single harmless no-op function (and NO project tools). The model is judged
 * [ModelCapability.AGENTIC] only when it responds with a call to that exact probe function whose
 * arguments parse as valid JSON.
 *
 * This is deliberately conservative:
 * - No probe-function call, or a call to any other function → [ModelCapability.COMPLETION_ONLY].
 * - Malformed JSON arguments → [ModelCapability.COMPLETION_ONLY].
 * - Transport/remote failure → [ModelCapability.UNKNOWN] (never silently promote to AGENTIC).
 *
 * Never invoked automatically: the settings card wires this behind the explicit
 * "Verify tool support" button so no hidden token usage is ever incurred (Global Constraint).
 */
object ModelCapabilityVerifier {

    const val PROBE_FUNCTION_NAME = "clawdea_capability_probe"

    private const val PROBE_INSTRUCTION =
        "Call the $PROBE_FUNCTION_NAME function with {\"ok\": true} to confirm you support tool calls."

    /** Build the single-tool probe request for [modelId]. Exposed for the production HTTP path. */
    private fun probeRequest(modelId: String): AgentCompletionRequest {
        val params = JsonObject().apply {
            addProperty("type", "object")
            add(
                "properties",
                JsonObject().apply {
                    add(
                        "ok",
                        JsonObject().apply {
                            addProperty("type", "boolean")
                            addProperty("description", "Always true.")
                        },
                    )
                },
            )
            add("required", JsonArray().apply { add("ok") })
        }
        return AgentCompletionRequest(
            model = modelId,
            messages = listOf(AgentMessage(role = "user", content = PROBE_INSTRUCTION)),
            tools = listOf(
                OpenAiToolDefinition(
                    type = "function",
                    function = OpenAiFunctionDefinition(
                        name = PROBE_FUNCTION_NAME,
                        description = "No-op capability probe. Returns nothing.",
                        parameters = params,
                    ),
                ),
            ),
            maxTokens = 256,
        )
    }

    /**
     * Verify capability by streaming one probe request through [client].
     * Pure of IntelliJ concerns so it's unit-testable with a fake [AgentClient].
     */
    fun verify(client: AgentClient, modelId: String): ModelCapability = runBlocking {
        val assembler = ToolCallAssembler()
        var failed = false
        try {
            client.stream(probeRequest(modelId)).collect { event ->
                when (event) {
                    is AgentStreamEvent.ToolFragment -> assembler.accept(event)
                    is AgentStreamEvent.Failure -> failed = true
                    else -> Unit // ignore text/reasoning/usage/finished
                }
            }
        } catch (_: Exception) {
            failed = true
        }

        if (failed) return@runBlocking ModelCapability.UNKNOWN

        val calls = assembler.completed()
        val probeCall = calls.firstOrNull { it.name == PROBE_FUNCTION_NAME }
            ?: return@runBlocking ModelCapability.COMPLETION_ONLY

        val validJson = try {
            JsonParser.parseString(probeCall.argumentsJson).isJsonObject
        } catch (_: Exception) {
            false
        }
        if (validJson) ModelCapability.AGENTIC else ModelCapability.COMPLETION_ONLY
    }

    /**
     * Production entry point: verify against a live provider [profile]/[credential] using the real
     * streaming HTTP client. Runs on the caller's worker thread (never the EDT).
     */
    fun verify(
        profile: ResolvedProviderProfile,
        credential: String,
        modelId: String,
        httpClient: OpenAiCompatibleClient = OpenAiCompatibleClient(),
    ): ModelCapability {
        val client = object : AgentClient {
            override suspend fun stream(request: AgentCompletionRequest) =
                httpClient.streamAgentCompletion(profile, credential, request)
        }
        return verify(client, modelId)
    }
}
