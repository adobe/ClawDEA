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

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Parses the OpenAI `codex app-server` JSON-RPC **notification** stream into ClawDEA's normalized
 * [CliEvent] hierarchy, so the codex backend renders through the same [CliBridge] / ChatPanel path
 * as Claude.
 *
 * [CodexAppServerProcess] forwards only server → client *notifications* (a JSON-RPC message with a
 * `method` and no `id`); responses and server-initiated requests are consumed by the process. Each
 * forwarded line is a `{"jsonrpc":"2.0","method":"...","params":{...}}` object and this parser
 * switches on `method`:
 *
 *  - `thread/started`             → [CliEvent.SystemInit] (session id for `thread/resume`)
 *  - `item/agentMessage/delta`    → [CliEvent.TextDelta] (per-token streaming — the app-server win
 *                                    over `exec`, which only emitted whole messages)
 *  - `item/reasoning/textDelta` + `summaryTextDelta` → [CliEvent.ReasoningDelta] (thinking stream)
 *  - `item/started`               → [CliEvent.AssistantMessage] with a tool use (shell / MCP / patch)
 *  - `item/completed`             → whole [CliEvent.AssistantMessage] (agent text) or
 *                                    [CliEvent.ToolResult] (shell / MCP / patch outcome)
 *  - `thread/tokenUsage/updated`  → stashed; applied to the next [CliEvent.Result]
 *  - `turn/completed`             → [CliEvent.Result] (carries the stashed per-turn token usage)
 *  - `error`                      → [CliEvent.AuthFailure] or an error [CliEvent.Result]
 *
 * Anything else is a deliberately-ignored [CliEvent.Unknown] with a **blank** `rawJson` so the
 * ChatPanel's Unknown branch early-returns instead of trying to render lifecycle chatter.
 *
 * [modelId] is stamped onto every [CliEvent.AssistantMessage] (the stream does not echo the model)
 * so the cost footer can label the turn.
 */
class CodexAppServerParser(private val modelId: String = "") : AgentEventParser {

    // Per-turn token usage stashed from `thread/tokenUsage/updated`, consumed at `turn/completed`.
    // The parser is per-bridge (one instance per session), so holding this small amount of state
    // between the two notifications is safe and avoids threading usage through the process.
    private var pendingUsage: Usage? = null

    override fun parse(jsonLine: String): CliEvent {
        val obj = try {
            JsonParser.parseString(jsonLine).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return ignored("")

        val method = obj.str("method") ?: return ignored("")
        val params = obj.getAsJsonObjectOrNull("params") ?: JsonObject()

        return when (method) {
            "thread/started" -> {
                val thread = params.getAsJsonObjectOrNull("thread")
                CliEvent.SystemInit(
                    sessionId = thread?.str("id") ?: params.str("threadId") ?: "",
                    model = modelId,
                    tools = emptyList(),
                )
            }

            "item/agentMessage/delta" -> {
                val delta = params.str("delta") ?: ""
                if (delta.isEmpty()) ignored(method) else CliEvent.TextDelta(text = delta)
            }

            // Reasoning/thinking stream — no Claude analogue; rendered as a collapsible section.
            "item/reasoning/textDelta" -> {
                val delta = params.str("delta") ?: ""
                if (delta.isEmpty()) ignored(method) else CliEvent.ReasoningDelta(text = delta)
            }
            "item/reasoning/summaryTextDelta" -> {
                val delta = params.str("delta") ?: ""
                if (delta.isEmpty()) ignored(method) else CliEvent.ReasoningDelta(text = delta, summary = true)
            }

            "item/started" -> parseItem(params, completed = false, method = method)
            "item/completed" -> parseItem(params, completed = true, method = method)

            "thread/tokenUsage/updated" -> {
                pendingUsage = parseUsage(params.getAsJsonObjectOrNull("tokenUsage"))
                ignored(method)
            }

            "turn/completed" -> parseTurnCompleted(params)

            "error" -> {
                val msg = params.str("message") ?: obj.str("message") ?: "codex error"
                if (looksLikeAuthFailure(msg)) CliEvent.AuthFailure(msg)
                else CliEvent.Result(text = msg, isError = true, costUsd = 0.0, sessionId = "")
            }

            else -> ignored(method)
        }
    }

    private fun parseItem(params: JsonObject, completed: Boolean, method: String): CliEvent {
        val item = params.getAsJsonObjectOrNull("item") ?: return ignored(method)
        val id = item.str("id") ?: ""
        return when (item.str("type")) {
            "agentMessage" ->
                // Text streamed via item/agentMessage/delta; on completion emit the whole message so
                // the ChatPanel flushes its streamed buffer (event.text is the no-delta fallback).
                if (completed) assistantText(item.str("text") ?: "") else ignored(method)

            "commandExecution" ->
                if (completed) {
                    val out = item.str("aggregatedOutput") ?: ""
                    val exit = item.intOrNull("exitCode")
                    val failed = item.str("status") == "failed" || (exit != null && exit != 0)
                    CliEvent.ToolResult(toolUseId = id, content = out, isError = failed)
                } else {
                    // Name it "Bash" (not "shell") for render + filesystem-refresh parity with Claude.
                    toolUse(id, name = "Bash", input = jsonObj("command" to (item.str("command") ?: "")))
                }

            "mcpToolCall" ->
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

            "fileChange" ->
                // Codex's own patch mechanism (bypassing ClawDEA's propose_* MCP gate). Gated via
                // CodexApprovalGate (Phase C); surfaced as a generic tool block so the change is visible.
                if (completed) {
                    val failed = item.str("status") == "failed"
                    CliEvent.ToolResult(toolUseId = id, content = fileChangeSummary(item), isError = failed)
                } else {
                    toolUse(id, name = "apply_patch", input = jsonObj("changes" to fileChangeSummary(item)))
                }

            // reasoning content streams via item/reasoning/*Delta (→ ReasoningDelta); the reasoning
            // item/started|completed lifecycle carries no extra text. plan / web-search have no
            // CliEvent analogue yet.
            else -> ignored(method)
        }
    }

    private fun parseTurnCompleted(params: JsonObject): CliEvent {
        val turn = params.getAsJsonObjectOrNull("turn")
        val failed = turn?.str("status") == "failed"
        val errorMsg = turn?.getAsJsonObjectOrNull("error")?.str("message")
        val usage = pendingUsage
        pendingUsage = null
        return CliEvent.Result(
            text = if (failed) (errorMsg ?: "turn failed") else "",
            isError = failed,
            costUsd = 0.0, // derived from pricing × tokens by CostTracker (flat-rate subscription: notional)
            sessionId = "", // CliBridge keeps the thread id from SystemInit
            contextTokens = usage?.totalTokens ?: 0,
            contextWindow = usage?.contextWindow ?: 0,
            inputTokens = usage?.inputTokens ?: 0,
            outputTokens = (usage?.outputTokens ?: 0) + (usage?.reasoningTokens ?: 0),
            cacheReadTokens = usage?.cachedTokens ?: 0,
            cacheCreationTokens = 0,
        )
    }

    private fun parseUsage(tokenUsage: JsonObject?): Usage? {
        tokenUsage ?: return null
        val last = tokenUsage.getAsJsonObjectOrNull("last")
        val total = tokenUsage.getAsJsonObjectOrNull("total")
        return Usage(
            inputTokens = last?.intOrNull("inputTokens") ?: 0,
            cachedTokens = last?.intOrNull("cachedInputTokens") ?: 0,
            outputTokens = last?.intOrNull("outputTokens") ?: 0,
            reasoningTokens = last?.intOrNull("reasoningOutputTokens") ?: 0,
            // `total.totalTokens` best approximates the live context fill for the budget indicator.
            totalTokens = total?.intOrNull("totalTokens") ?: last?.intOrNull("totalTokens") ?: 0,
            contextWindow = tokenUsage.intOrNull("modelContextWindow") ?: 0,
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

    /** A deliberately-ignored notification: blank rawJson makes the ChatPanel Unknown branch a no-op. */
    private fun ignored(method: String): CliEvent = CliEvent.Unknown(rawType = method, rawJson = "")

    private fun looksLikeAuthFailure(text: String): Boolean {
        val lower = text.lowercase()
        return AUTH_ERROR_PHRASES.any { lower.contains(it) }
    }

    private data class Usage(
        val inputTokens: Int,
        val cachedTokens: Int,
        val outputTokens: Int,
        val reasoningTokens: Int,
        val totalTokens: Int,
        val contextWindow: Int,
    )

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

        private fun fileChangeSummary(item: JsonObject): String {
            val changes = item.get("changes")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
            return changes.mapNotNull { el ->
                (el as? JsonObject)?.let { c ->
                    val kind = c.str("kind") ?: "update"
                    val path = c.str("path") ?: ""
                    "$kind $path".trim()
                }
            }.joinToString("\n")
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
    }
}
