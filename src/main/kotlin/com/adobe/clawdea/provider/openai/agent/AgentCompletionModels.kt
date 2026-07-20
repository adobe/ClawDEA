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

import com.google.gson.JsonObject

/**
 * Stream events from agentic Chat Completions.
 */
sealed interface AgentStreamEvent {
    data class Text(val text: String) : AgentStreamEvent
    data class Reasoning(val text: String) : AgentStreamEvent
    data class ToolFragment(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: String,
    ) : AgentStreamEvent
    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
        val cachedInputTokens: Int,
        val reasoningTokens: Int,
    ) : AgentStreamEvent
    data class Finished(val reason: String?) : AgentStreamEvent
    data class Failure(val status: Int?, val message: String, val retryAfterSeconds: Long?) : AgentStreamEvent
}

/**
 * Request to agent-capable Chat Completions endpoint.
 */
data class AgentCompletionRequest(
    val model: String,
    val messages: List<AgentMessage>,
    val tools: List<OpenAiToolDefinition>,
    val maxTokens: Int,
    // When false, the request body sets `stream:false` and omits the streaming-only `stream_options`.
    // Defaulted true so process backends and existing constructions are unaffected.
    val stream: Boolean = true,
)

/**
 * Message in agent conversation.
 */
data class AgentMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<AgentToolCall> = emptyList(),
    val toolCallId: String? = null,
)

/**
 * Tool call (complete or partial).
 */
data class AgentToolCall(val id: String, val name: String, val argumentsJson: String)

/**
 * Token usage metrics.
 */
data class AgentUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val reasoningTokens: Int = 0,
)

/**
 * Tool definition for function calling.
 */
data class OpenAiToolDefinition(
    val type: String = "function",
    val function: OpenAiFunctionDefinition,
)

/**
 * Function definition.
 */
data class OpenAiFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)
