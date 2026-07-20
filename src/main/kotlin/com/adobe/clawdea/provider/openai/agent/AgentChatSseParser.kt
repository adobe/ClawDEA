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
import com.google.gson.JsonParser

/**
 * Parser for agentic Chat Completions responses in BOTH shapes returned by OpenAI-compatible
 * gateways:
 *  - **SSE** ([parse]): `data: {...}` framed chunks whose payload lives in `choices[0].delta.*`.
 *  - **Non-streamed** ([parseNonStreamedCompletion]): a single `chat.completion` JSON object whose
 *    payload lives in `choices[0].message.*`. Some gateways ignore `stream:true` and return this.
 * Returns null / empty for keepalives, blank lines, and unrecognized content.
 * Never logs raw content (contains prompts and generated text).
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
                return AgentStreamEvent.Failure(null, extractErrorMessage(root), null)
            }

            // Check for usage
            if (root.has("usage")) {
                return parseUsage(root.getAsJsonObject("usage"))
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

    /**
     * Parse a single, NON-streamed `chat.completion` JSON object into the same ordered
     * [AgentStreamEvent]s the SSE path emits, so [AgentLoopController] consumes either shape
     * unchanged. Unlike the streaming shape, payload lives in `choices[0].message.*` (not `.delta`),
     * and a `tool_call` arrives complete (full id+name+arguments) — [ToolCallAssembler] stitches a
     * single complete fragment per index correctly.
     *
     * Emission order mirrors the stream closely enough for the loop: tool fragments (if any) OR
     * text/reasoning, then Usage, then Finished. Returns an empty list for a malformed body or a
     * body with no usable `choices[0].message`.
     *
     * On a top-level `error` object, returns a single [AgentStreamEvent.Failure].
     */
    fun parseNonStreamedCompletion(json: String): List<AgentStreamEvent> {
        return try {
            val root = JsonParser.parseString(json).asJsonObject

            // Top-level error takes precedence — surface it as a Failure and stop.
            if (root.has("error")) {
                return listOf(AgentStreamEvent.Failure(null, extractErrorMessage(root), null))
            }

            val choices = root.getAsJsonArray("choices")
            if (choices == null || choices.isEmpty) {
                // No choices and no `error` key: surface a `detail`/`details` message if present
                // (some gateways return 200 with a bare error/detail body), else a generic failure —
                // never silently yield nothing (that surfaced to the user as an empty answer).
                val detail = detailMessage(root)
                return listOf(AgentStreamEvent.Failure(null, detail ?: "Provider returned no choices", null))
            }

            val choice = choices[0].asJsonObject
            val message = choice.getAsJsonObject("message") ?: return emptyList()

            val events = mutableListOf<AgentStreamEvent>()

            // reasoning_content (if present) before text — the loop buffers reasoning separately.
            val reasoning = message.get("reasoning_content")
            if (reasoning != null && !reasoning.isJsonNull && reasoning.asString.isNotBlank()) {
                events.add(AgentStreamEvent.Reasoning(reasoning.asString))
            }

            // content: emit as Text if non-blank.
            val content = message.get("content")
            if (content != null && !content.isJsonNull && content.asString.isNotBlank()) {
                events.add(AgentStreamEvent.Text(content.asString))
            }

            // tool_calls: each carries a complete id+name+arguments in one shot. Emit one
            // ToolFragment per call with its real index (positional) so the assembler stitches
            // exactly one complete tool call per index.
            val toolCalls = message.getAsJsonArray("tool_calls")
            if (toolCalls != null) {
                toolCalls.forEachIndexed { index, element ->
                    val toolCall = element.asJsonObject
                    val id = toolCall.get("id")?.asString
                    val callIndex = toolCall.get("index")?.asInt ?: index
                    val function = toolCall.getAsJsonObject("function")
                    val name = function?.get("name")?.asString
                    val arguments = function?.get("arguments")?.asString ?: ""
                    events.add(AgentStreamEvent.ToolFragment(callIndex, id, name, arguments))
                }
            }

            // Usage (top-level, same mapping as the SSE path).
            if (root.has("usage") && !root.get("usage").isJsonNull) {
                events.add(parseUsage(root.getAsJsonObject("usage")))
            }

            // Finished with the reported finish_reason (default "stop").
            val finishReason = choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString
            events.add(AgentStreamEvent.Finished(finishReason ?: "stop"))

            events
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Extract a human-readable message from a top-level `error`, which gateways shape inconsistently:
     * a plain string (`"error": "..."`), or an object (`"error": {"message": "..."}` / `{"detail": ...}`).
     * Also appends a bounded `detail`/`details` sibling when present (LiteLLM/FastAPI put the real
     * cause there — often a stack trace; truncated so a huge body doesn't flood the UI/log).
     */
    private fun extractErrorMessage(root: JsonObject): String {
        val error = root.get("error")
        val base = when {
            error == null || error.isJsonNull -> "Unknown error"
            error.isJsonPrimitive -> error.asString
            error.isJsonObject -> error.asJsonObject.get("message")?.takeIf { !it.isJsonNull }?.asString
                ?: error.asJsonObject.get("detail")?.takeIf { !it.isJsonNull }?.asString
                ?: error.toString().take(200)
            else -> "Unknown error"
        }
        val detail = detailMessage(root)
        return if (detail != null && detail != base) "$base — $detail" else base
    }

    /** A top-level `detail`/`details` string, trimmed and bounded (never the full multi-KB body). */
    private fun detailMessage(root: JsonObject): String? {
        val el = root.get("detail") ?: root.get("details") ?: return null
        if (el.isJsonNull || !el.isJsonPrimitive || !el.asJsonPrimitive.isString) return null
        val s = el.asString.trim()
        if (s.isEmpty()) return null
        return if (s.length > 300) s.take(300) + "…" else s
    }

    /** Map a `usage` JSON object to [AgentStreamEvent.Usage]. Shared by both response shapes. */
    private fun parseUsage(usage: JsonObject): AgentStreamEvent.Usage {
        val inputTokens = usage.get("prompt_tokens")?.asInt ?: 0
        val outputTokens = usage.get("completion_tokens")?.asInt ?: 0
        val cachedInputTokens = usage.getAsJsonObject("prompt_tokens_details")
            ?.get("cached_tokens")?.asInt ?: 0
        val reasoningTokens = usage.getAsJsonObject("completion_tokens_details")
            ?.get("reasoning_tokens")?.asInt ?: 0
        return AgentStreamEvent.Usage(inputTokens, outputTokens, cachedInputTokens, reasoningTokens)
    }
}
