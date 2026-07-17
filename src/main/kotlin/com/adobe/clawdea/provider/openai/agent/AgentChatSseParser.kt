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

import com.google.gson.JsonParser

/**
 * Parser for SSE lines from agentic Chat Completions streams.
 * Returns null for keepalives, blank lines, and unrecognized content.
 * Never logs raw SSE content (contains prompts and generated text).
 */
class AgentChatSseParser {
    /**
     * Parse a single SSE line.
     * Precedence when multiple fields present: tool_calls > reasoning_content > content.
     * The delta payload is extracted BEFORE finish_reason so a final chunk that carries BOTH a
     * delta and a non-null finish_reason (common in OpenAI/vLLM streams) does not drop its trailing
     * content. finish_reason is surfaced only when the chunk has no delta payload.
     * Returns null for blank lines, comments, [DONE], and unrecognized lines.
     */
    fun parse(line: String): AgentStreamEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) {
            return null
        }

        val data = if (trimmed.startsWith("data: ")) {
            trimmed.substring(6)
        } else {
            return null
        }

        if (data == "[DONE]") {
            return null
        }

        return try {
            val root = JsonParser.parseString(data).asJsonObject

            // Check for top-level error
            if (root.has("error")) {
                val error = root.getAsJsonObject("error")
                val message = error.get("message")?.asString ?: "Unknown error"
                return AgentStreamEvent.Failure(null, message, null)
            }

            // Check for usage
            if (root.has("usage")) {
                val usage = root.getAsJsonObject("usage")
                val inputTokens = usage.get("prompt_tokens")?.asInt ?: 0
                val outputTokens = usage.get("completion_tokens")?.asInt ?: 0
                val cachedInputTokens = usage.getAsJsonObject("prompt_tokens_details")
                    ?.get("cached_tokens")?.asInt ?: 0
                val reasoningTokens = usage.getAsJsonObject("completion_tokens_details")
                    ?.get("reasoning_tokens")?.asInt ?: 0
                return AgentStreamEvent.Usage(inputTokens, outputTokens, cachedInputTokens, reasoningTokens)
            }

            // Parse choices[0]
            val choices = root.getAsJsonArray("choices")
            if (choices == null || choices.isEmpty) {
                return null
            }

            val choice = choices[0].asJsonObject

            // Parse delta FIRST. The final chunk of an OpenAI/vLLM stream can carry BOTH a
            // delta payload AND a non-null finish_reason; extracting the delta before handling
            // finish_reason guarantees that trailing content/reasoning/tool fragment is not dropped.
            // Precedence: tool_calls > reasoning_content > content
            val delta = choice.getAsJsonObject("delta")
            if (delta != null) {
                if (delta.has("tool_calls")) {
                    val toolCalls = delta.getAsJsonArray("tool_calls")
                    if (toolCalls != null && !toolCalls.isEmpty) {
                        val toolCall = toolCalls[0].asJsonObject
                        val index = toolCall.get("index")?.asInt ?: return null
                        val id = toolCall.get("id")?.asString
                        val function = toolCall.getAsJsonObject("function")
                        val name = function?.get("name")?.asString
                        val arguments = function?.get("arguments")?.asString ?: ""
                        return AgentStreamEvent.ToolFragment(index, id, name, arguments)
                    }
                }

                if (delta.has("reasoning_content")) {
                    val reasoning = delta.get("reasoning_content")
                    if (reasoning != null && !reasoning.isJsonNull) {
                        return AgentStreamEvent.Reasoning(reasoning.asString)
                    }
                }

                if (delta.has("content")) {
                    val content = delta.get("content")
                    if (content != null && !content.isJsonNull && !content.asString.isEmpty()) {
                        return AgentStreamEvent.Text(content.asString)
                    }
                }
            }

            // No delta payload — surface finish_reason if present (terminal chunk).
            val finishReason = choice.get("finish_reason")
            if (finishReason != null && !finishReason.isJsonNull) {
                return AgentStreamEvent.Finished(finishReason.asString)
            }

            null
        } catch (_: Exception) {
            null
        }
    }
}
