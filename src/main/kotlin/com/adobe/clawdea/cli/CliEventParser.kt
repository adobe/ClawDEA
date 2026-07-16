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

class CliEventParser : AgentEventParser {

    override fun parse(jsonLine: String): CliEvent {
        try {
            val type = extractString(jsonLine, "\"type\"") ?: ""
            return when (type) {
                "system" -> parseSystem(jsonLine)
                "stream_event" -> parseStreamEvent(jsonLine)
                "assistant" -> parseAssistantMessage(jsonLine)
                "user" -> parseUserMessage(jsonLine)
                "result" -> parseResult(jsonLine)
                else -> CliEvent.Unknown(rawType = type, rawJson = jsonLine)
            }
        } catch (e: Exception) {
            return CliEvent.Unknown(rawType = "", rawJson = jsonLine)
        }
    }

    private fun parseSystem(json: String): CliEvent {
        val subtype = extractString(json, "\"subtype\"")
        if (subtype == "error") {
            val errorType = extractNestedString(json, "\"error\"", "\"type\"")?.lowercase() ?: ""
            if (AUTH_ERROR_TYPES.any { errorType.contains(it) }) {
                val message = extractNestedString(json, "\"error\"", "\"message\"") ?: "authentication failed"
                return CliEvent.AuthFailure(message)
            }
        }
        // Background sub-agent (run_in_background Agent) lifecycle. These carry a
        // tool_use_id equal to the dispatching Agent's id (the sub-agent card id),
        // letting EventStreamHandler keep the card open past the turn Result and
        // finalize it on the real completion. Not init — must not fall through to
        // SystemInit (which would churn currentModel / "Connected" on every tick).
        when (subtype) {
            "task_started", "task_progress", "task_updated", "task_notification" -> {
                return parseBackgroundTask(json, subtype)
            }
        }
        // Roster snapshot (background_tasks_changed) and any other system subtype
        // carry no per-card action for us; ignore without disturbing session state.
        if (subtype != null && subtype != "init") {
            return CliEvent.Unknown(rawType = "system:$subtype", rawJson = "")
        }
        val sessionId = extractString(json, "\"session_id\"") ?: ""
        val model = extractString(json, "\"model\"") ?: ""
        val tools = extractStringArray(json, "\"tools\"")
        return CliEvent.SystemInit(sessionId, model, tools)
    }

    private fun parseBackgroundTask(json: String, subtype: String): CliEvent {
        // task_updated keys off task_id and carries no tool_use_id — it only nudges
        // the roster; the actionable transition always also arrives as a
        // task_notification (tool_use_id + status). Ignore task_updated to avoid a
        // card event with no id to route by.
        val toolUseId = extractString(json, "\"tool_use_id\"")
            ?: return CliEvent.Unknown(rawType = "system:$subtype", rawJson = "")
        val phase = when (subtype) {
            "task_started" -> CliEvent.BackgroundTask.Phase.STARTED
            "task_progress" -> CliEvent.BackgroundTask.Phase.PROGRESS
            else -> CliEvent.BackgroundTask.Phase.NOTIFICATION
        }
        val status = extractString(json, "\"status\"")
        val summary = extractString(json, "\"summary\"")
        val lastToolName = extractString(json, "\"last_tool_name\"")
        val usageBlock = extractObject(json, "\"usage\"")
        val toolUses = usageBlock?.let { extractNumber(it, "\"tool_uses\"").toInt() } ?: 0
        return CliEvent.BackgroundTask(
            toolUseId = toolUseId,
            phase = phase,
            status = status,
            summary = summary,
            lastToolName = lastToolName,
            toolUses = toolUses,
        )
    }

    private fun parseStreamEvent(json: String): CliEvent {
        val eventType = extractNestedString(json, "\"event\"", "\"type\"")
        if (eventType == "content_block_delta") {
            val deltaType = extractNestedString(json, "\"delta\"", "\"type\"")
            if (deltaType == "text_delta") {
                val text = extractNestedString(json, "\"delta\"", "\"text\"") ?: ""
                return CliEvent.TextDelta(text, extractString(json, "\"parent_tool_use_id\""))
            }
        }
        return CliEvent.Unknown(rawType = "stream_event", rawJson = json)
    }

    private fun parseAssistantMessage(json: String): CliEvent {
        val parentToolUseId = extractString(json, "\"parent_tool_use_id\"")
        // model lives inside the nested `message` object (message.model).
        val model = extractNestedString(json, "\"message\"", "\"model\"") ?: ""
        val contentArray = extractContentArray(json)
        var text = ""
        val toolUses = mutableListOf<CliEvent.ToolUse>()

        for (block in contentArray) {
            val blockType = extractString(block, "\"type\"")
            when (blockType) {
                "text" -> {
                    val t = extractString(block, "\"text\"")
                    if (t != null) text = t
                }
                "tool_use" -> {
                    val id = extractString(block, "\"id\"") ?: ""
                    val name = extractString(block, "\"name\"") ?: ""
                    val input = extractObject(block, "\"input\"") ?: "{}"
                    toolUses.add(CliEvent.ToolUse(id, name, input))
                }
            }
        }

        return CliEvent.AssistantMessage(text, toolUses, parentToolUseId, model)
    }

    private fun parseUserMessage(json: String): CliEvent {
        val parentToolUseId = extractString(json, "\"parent_tool_use_id\"")
        val contentArray = extractContentArray(json)
        val textParts = mutableListOf<String>()
        for (block in contentArray) {
            val blockType = extractString(block, "\"type\"")
            if (blockType == "tool_result") {
                val toolUseId = extractString(block, "\"tool_use_id\"") ?: ""
                val content = extractToolResultContent(block)
                val isError = block.contains("\"is_error\":true")
                return CliEvent.ToolResult(toolUseId, content, isError, parentToolUseId)
            }
            if (blockType == "text") {
                extractString(block, "\"text\"")?.let { textParts.add(it) }
            }
        }
        val text = textParts.joinToString("\n").trim()
        if (text.startsWith(GOAL_FEEDBACK_PREFIX)) {
            return parseGoalFeedback(text)
        }
        return CliEvent.Unknown(rawType = "user", rawJson = json)
    }

    /** Parse `Stop hook feedback:\n[<condition>]: <reason>` into a [CliEvent.GoalFeedback]. */
    private fun parseGoalFeedback(text: String): CliEvent.GoalFeedback {
        val body = text.removePrefix(GOAL_FEEDBACK_PREFIX).trim()
        // Assumes the CC format `[<condition>]: <reason>`; a literal ']' inside
        // the condition would split early — acceptable for this wire format.
        val open = body.indexOf('[')
        val close = body.indexOf(']')
        if (open == 0 && close > open) {
            val condition = body.substring(open + 1, close)
            val reason = body.substring(close + 1).removePrefix(":").trim()
            return CliEvent.GoalFeedback(condition, reason)
        }
        return CliEvent.GoalFeedback("", body)
    }

    /**
     * The Anthropic Messages API allows tool_result `content` to be either a string
     * or an array of `{"type":"text","text":...}` blocks. The Claude CLI forwards
     * whichever form the MCP server emitted; both must be supported.
     */
    private fun extractToolResultContent(block: String): String {
        extractString(block, "\"content\"")?.let { return it }
        return extractContentArray(block)
            .filter { extractString(it, "\"type\"") == "text" }
            .mapNotNull { extractString(it, "\"text\"") }
            .joinToString("\n")
    }

    private fun parseResult(json: String): CliEvent {
        val typeResultIndex = json.indexOf("\"type\":\"result\"")
        val searchStart = if (typeResultIndex != -1) typeResultIndex + 15 else 0
        val resultSubstring = json.substring(searchStart)
        val text = extractString(resultSubstring, "\"result\"") ?: ""
        val isError = json.contains("\"is_error\":true")
        val costUsd = extractNumber(json, "\"total_cost_usd\"")
        val sessionId = extractString(json, "\"session_id\"") ?: ""
        val usageStart = json.indexOf("\"usage\"")
        val contextTokens: Int
        val inputTokens: Int
        val outputTokens: Int
        val cacheReadTokens: Int
        val cacheCreationTokens: Int
        if (usageStart != -1) {
            val usageBlock = json.substring(usageStart)
            val input = extractNumber(usageBlock, "\"input_tokens\"").toInt()
            val cacheRead = extractNumber(usageBlock, "\"cache_read_input_tokens\"").toInt()
            val cacheCreate = extractNumber(usageBlock, "\"cache_creation_input_tokens\"").toInt()
            val output = extractNumber(usageBlock, "\"output_tokens\"").toInt()
            contextTokens = input + cacheRead + cacheCreate
            inputTokens = input
            outputTokens = output
            cacheReadTokens = cacheRead
            cacheCreationTokens = cacheCreate
        } else {
            contextTokens = 0
            inputTokens = 0
            outputTokens = 0
            cacheReadTokens = 0
            cacheCreationTokens = 0
        }
        // contextWindow lives inside modelUsage.<model>.contextWindow. Search after
        // the modelUsage marker so we don't accidentally read a numeric field with
        // that name from elsewhere; if absent, the caller falls back to a default.
        val modelUsageStart = json.indexOf("\"modelUsage\"")
        val contextWindow = if (modelUsageStart != -1) {
            extractNumber(json.substring(modelUsageStart), "\"contextWindow\"").toInt()
        } else 0
        if (isError && looksLikeAuthFailure(text)) {
            return CliEvent.AuthFailure(text)
        }
        return CliEvent.Result(text, isError, costUsd, sessionId, contextTokens, contextWindow, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens)
    }

    private fun looksLikeAuthFailure(text: String): Boolean {
        val lower = text.lowercase()
        return AUTH_ERROR_PHRASES.any { lower.contains(it) }
    }

    private fun extractString(json: String, key: String): String? {
        val keyIndex = json.indexOf(key)
        if (keyIndex == -1) return null
        val colonIndex = json.indexOf(':', keyIndex + key.length)
        if (colonIndex == -1) return null
        val afterColon = json.substring(colonIndex + 1).trimStart()
        if (afterColon.isEmpty() || afterColon[0] != '"') return null
        val sb = StringBuilder()
        var i = 1
        while (i < afterColon.length) {
            val c = afterColon[i]
            if (c == '\\' && i + 1 < afterColon.length) {
                val next = afterColon[i + 1]
                when (next) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '/' -> sb.append('/')
                    else -> { sb.append('\\'); sb.append(next) }
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun extractNestedString(json: String, outerKey: String, innerKey: String): String? {
        val outerIndex = json.indexOf(outerKey)
        if (outerIndex == -1) return null
        val remainder = json.substring(outerIndex)
        return extractString(remainder, innerKey)
    }

    private fun extractStringArray(json: String, key: String): List<String> {
        val keyIndex = json.indexOf(key)
        if (keyIndex == -1) return emptyList()
        val bracketStart = json.indexOf('[', keyIndex)
        if (bracketStart == -1) return emptyList()
        val bracketEnd = json.indexOf(']', bracketStart)
        if (bracketEnd == -1) return emptyList()
        val arrayContent = json.substring(bracketStart + 1, bracketEnd)
        return Regex("\"([^\"]+)\"").findAll(arrayContent).map { it.groupValues[1] }.toList()
    }

    private fun extractNumber(json: String, key: String): Double {
        val keyIndex = json.indexOf(key)
        if (keyIndex == -1) return 0.0
        val colonIndex = json.indexOf(':', keyIndex + key.length)
        if (colonIndex == -1) return 0.0
        val afterColon = json.substring(colonIndex + 1).trimStart()
        val numStr = afterColon.takeWhile { it.isDigit() || it == '.' || it == '-' || it == 'e' || it == 'E' }
        return numStr.toDoubleOrNull() ?: 0.0
    }

    private fun extractContentArray(json: String): List<String> {
        val contentKey = "\"content\":["
        val start = json.indexOf(contentKey)
        if (start == -1) return emptyList()
        val arrayStart = start + contentKey.length - 1
        val arrayEnd = findMatchingClose(json, arrayStart, '[', ']')
        if (arrayEnd == -1) return emptyList()
        val arrayContent = json.substring(arrayStart + 1, arrayEnd)

        val objects = mutableListOf<String>()
        var i = 0
        while (i < arrayContent.length) {
            when (arrayContent[i]) {
                '"' -> i = skipString(arrayContent, i)
                '{' -> {
                    val end = findMatchingClose(arrayContent, i, '{', '}')
                    if (end != -1) {
                        objects.add(arrayContent.substring(i, end + 1))
                        i = end
                    }
                }
            }
            i++
        }
        return objects
    }

    private fun extractObject(json: String, key: String): String? {
        val keyIndex = json.indexOf(key)
        if (keyIndex == -1) return null
        val colonIndex = json.indexOf(':', keyIndex + key.length)
        if (colonIndex == -1) return null
        val afterColon = json.substring(colonIndex + 1).trimStart()
        if (afterColon.isEmpty() || afterColon[0] != '{') return null
        val end = findMatchingClose(afterColon, 0, '{', '}')
        return if (end != -1) afterColon.substring(0, end + 1) else null
    }

    private fun findMatchingClose(json: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = start
        while (i < json.length) {
            when (json[i]) {
                '"' -> i = skipString(json, i)
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun skipString(json: String, quoteIndex: Int): Int {
        var i = quoteIndex + 1
        while (i < json.length) {
            if (json[i] == '\\') { i += 2; continue }
            if (json[i] == '"') return i
            i++
        }
        return i
    }

    companion object {
        private const val GOAL_FEEDBACK_PREFIX = "Stop hook feedback:"

        private val AUTH_ERROR_TYPES = listOf(
            "authentication_error",
            "invalid_api_key",
            "subscription_expired",
            "unauthorized",
        )

        // Deliberately narrow — hunting for phrasing specific to credential problems,
        // not generic uses of the word "auth".
        private val AUTH_ERROR_PHRASES = listOf(
            "credentials have expired",
            "please log in",
            "please sign in",
            "subscription expired",
            "invalid token",
            "invalid api key",
            "401 unauthorized",
            "not authenticated",
        )
    }
}
