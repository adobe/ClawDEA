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

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Parses the OpenAI `codex exec --json` stdout stream (the thread / turn / item
 * schema) into ClawDEA's normalized [CliEvent] hierarchy, so the codex backend
 * renders through the same [CliBridge] / ChatPanel path as Claude.
 *
 * Unlike [CliEventParser] (manual string extraction for Claude's high-volume,
 * per-token `stream-json`), the codex stream is **coarse-grained** — a whole item
 * completes at once, there are no per-token text deltas — so this parser uses Gson
 * for robust nested extraction (`item.result.content[].text`, `usage`, etc.). The
 * event shapes are pinned by the spike in
 * docs/superpowers/specs/2026-07-14-codex-interface-findings.md (## Phase 2 spike closure).
 *
 * [modelId] is stamped onto every [CliEvent.AssistantMessage] (codex does not echo
 * the model on the stream) so the cost footer can price the turn.
 */
class CodexEventParser(private val modelId: String = "") : AgentEventParser {

    override fun parse(jsonLine: String): CliEvent {
        val root = try {
            JsonParser.parseString(jsonLine)
        } catch (_: Exception) {
            return CliEvent.Unknown(rawType = "", rawJson = jsonLine)
        }
        if (!root.isJsonObject) return CliEvent.Unknown(rawType = "", rawJson = jsonLine)
        val obj = root.asJsonObject
        return when (obj.str("type")) {
            "thread.started" -> CliEvent.SystemInit(
                sessionId = obj.str("thread_id") ?: "",
                model = modelId,
                tools = emptyList(),
            )
            "item.started" -> parseItem(obj, jsonLine, completed = false)
            "item.completed" -> parseItem(obj, jsonLine, completed = true)
            "turn.completed" -> parseTurnCompleted(obj)
            "turn.failed" -> {
                val msg = obj.getAsJsonObjectOrNull("error")?.str("message") ?: "turn failed"
                if (looksLikeAuthFailure(msg)) CliEvent.AuthFailure(msg)
                else CliEvent.Result(text = msg, isError = true, costUsd = 0.0, sessionId = "")
            }
            "error" -> {
                val msg = obj.str("message") ?: ""
                if (looksLikeAuthFailure(msg)) CliEvent.AuthFailure(msg)
                else CliEvent.Unknown(rawType = "error", rawJson = jsonLine)
            }
            // turn.started and anything else are lifecycle-only.
            else -> CliEvent.Unknown(rawType = obj.str("type") ?: "", rawJson = jsonLine)
        }
    }

    private fun parseItem(obj: JsonObject, raw: String, completed: Boolean): CliEvent {
        val item = obj.getAsJsonObjectOrNull("item")
            ?: return CliEvent.Unknown(rawType = obj.str("type") ?: "", rawJson = raw)
        val id = item.str("id") ?: ""
        return when (item.str("type")) {
            "agent_message" ->
                // Emitted once, whole, on item.completed (no started phase / no deltas).
                if (completed) assistantText(item.str("text") ?: "")
                else CliEvent.Unknown(rawType = "item.started", rawJson = raw)

            "command_execution" ->
                if (completed) {
                    val out = item.str("aggregated_output") ?: ""
                    val exit = item.intOrNull("exit_code")
                    val failed = item.str("status") == "failed" || (exit != null && exit != 0)
                    CliEvent.ToolResult(toolUseId = id, content = out, isError = failed)
                } else {
                    val command = item.str("command") ?: ""
                    toolUse(id, name = "shell", input = jsonObj("command" to command))
                }

            "mcp_tool_call" ->
                if (completed) {
                    val error = item.getAsJsonObjectOrNull("error")
                    val failed = error != null || item.str("status") == "failed"
                    val content = if (error != null) error.str("message") ?: "MCP tool call failed"
                    else mcpResultText(item.getAsJsonObjectOrNull("result"))
                    CliEvent.ToolResult(toolUseId = id, content = content, isError = failed)
                } else {
                    val server = item.str("server") ?: ""
                    val tool = item.str("tool") ?: ""
                    val args = item.get("arguments")?.takeIf { it.isJsonObject }?.toString() ?: "{}"
                    toolUse(id, name = "mcp__${server}__${tool}", input = args)
                }

            // reasoning summaries and item-level errors have no CliEvent analogue yet.
            else -> CliEvent.Unknown(rawType = "item", rawJson = raw)
        }
    }

    private fun parseTurnCompleted(obj: JsonObject): CliEvent {
        val usage = obj.getAsJsonObjectOrNull("usage")
        val input = usage?.intOrNull("input_tokens") ?: 0
        val cached = usage?.intOrNull("cached_input_tokens") ?: 0
        val output = usage?.intOrNull("output_tokens") ?: 0
        val reasoning = usage?.intOrNull("reasoning_output_tokens") ?: 0
        return CliEvent.Result(
            text = "",
            isError = false,
            costUsd = 0.0,          // derived from pricing × tokens by CostTracker
            sessionId = "",          // CliBridge keeps the thread_id from SystemInit
            contextTokens = input,   // codex input_tokens already includes the cached subset
            contextWindow = 0,       // not on the stdout stream — caller falls back to a default
            inputTokens = input,
            outputTokens = output + reasoning,
            cacheReadTokens = cached,
            cacheCreationTokens = 0,
        )
    }

    private fun assistantText(text: String): CliEvent =
        CliEvent.AssistantMessage(text = text, toolUses = emptyList(), model = modelId)

    private fun toolUse(id: String, name: String, input: String): CliEvent =
        CliEvent.AssistantMessage(
            text = "",
            toolUses = listOf(CliEvent.ToolUse(id = id, name = name, input = input)),
            model = modelId,
        )

    private fun looksLikeAuthFailure(text: String): Boolean {
        val lower = text.lowercase()
        return AUTH_ERROR_PHRASES.any { lower.contains(it) }
    }

    private companion object {
        private val AUTH_ERROR_PHRASES = listOf(
            "401 unauthorized",
            "missing bearer",
            "not authenticated",
            "invalid api key",
            "invalid token",
            "please log in",
            "please sign in",
            "unauthorized",
        )

        private fun jsonObj(vararg pairs: Pair<String, String>): String {
            val o = JsonObject()
            for ((k, v) in pairs) o.addProperty(k, v)
            return o.toString()
        }

        private fun mcpResultText(result: JsonObject?): String {
            val content = result?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
            return content.mapNotNull { el ->
                (el as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
            }.joinToString("\n")
        }

        private fun JsonObject.str(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asString

        private fun JsonObject.intOrNull(key: String): Int? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

        private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
            get(key)?.takeIf { it.isJsonObject }?.asJsonObject

        @Suppress("unused")
        private fun JsonElement.orNull(): JsonElement? = takeIf { !it.isJsonNull }
    }
}
