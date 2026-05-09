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
// src/main/kotlin/com/adobe/clawdea/gateway/StreamingParser.kt
package com.adobe.clawdea.gateway

import com.intellij.openapi.diagnostic.Logger

/**
 * Parses Anthropic SSE (Server-Sent Events) stream lines into typed StreamEvents.
 *
 * SSE format:
 *   event: <type>\n
 *   data: <json>\n
 *   \n
 *
 * We parse line-by-line. Lines starting with "data: " contain JSON payloads.
 * Lines starting with "event: " indicate event type (e.g., ping).
 */
class StreamingParser {

    private val log = Logger.getInstance(StreamingParser::class.java)

    /**
     * Parse a single SSE line. Returns null for empty lines, comments, or unknown formats.
     */
    fun parseLine(line: String): StreamEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return null

        if (trimmed.startsWith("event: ")) {
            val eventType = trimmed.removePrefix("event: ").trim()
            if (eventType == "ping") return StreamEvent.Ping
            return null
        }

        if (!trimmed.startsWith("data: ")) return null
        val json = trimmed.removePrefix("data: ").trim()
        if (json == "[DONE]") return StreamEvent.MessageStop(stopReason = null)

        return try {
            parseJsonEvent(json)
        } catch (e: Exception) {
            log.warn("Failed to parse SSE JSON: $json", e)
            null
        }
    }

    private fun parseJsonEvent(json: String): StreamEvent? {
        // Minimal JSON parsing without external dependencies.
        // We extract "type" and relevant fields using string matching.
        val type = extractString(json, "\"type\"")

        return when (type) {
            "content_block_delta" -> {
                val deltaJson = extractObject(json, "\"delta\"")
                val deltaType = if (deltaJson != null) extractString(deltaJson, "\"type\"") else null
                if (deltaType == "text_delta") {
                    val text = extractString(deltaJson!!, "\"text\"")
                    if (text != null) StreamEvent.TextDelta(unescapeJson(text)) else null
                } else null
            }
            "content_block_start" -> {
                val index = extractInt(json, "\"index\"")
                StreamEvent.ContentBlockStart(index ?: 0)
            }
            "content_block_stop" -> {
                val index = extractInt(json, "\"index\"")
                StreamEvent.ContentBlockStop(index ?: 0)
            }
            "message_stop" -> StreamEvent.MessageStop(stopReason = null)
            "message_delta" -> {
                val deltaJson = extractObject(json, "\"delta\"")
                val stopReason = if (deltaJson != null) extractString(deltaJson, "\"stop_reason\"") else null
                StreamEvent.MessageStop(stopReason = stopReason)
            }
            "error" -> {
                val errorJson = extractObject(json, "\"error\"")
                val message = if (errorJson != null) extractString(errorJson, "\"message\"") else "Unknown error"
                StreamEvent.Error(message ?: "Unknown error")
            }
            "message_start" -> null // Ignore message_start
            "ping" -> StreamEvent.Ping
            else -> null
        }
    }

    companion object {
        /** Extract a string value for a given key from a JSON string. */
        fun extractString(json: String, key: String): String? {
            val keyIndex = json.indexOf(key)
            if (keyIndex == -1) return null
            val colonIndex = json.indexOf(':', keyIndex + key.length)
            if (colonIndex == -1) return null
            val startQuote = json.indexOf('"', colonIndex + 1)
            if (startQuote == -1) return null
            val endQuote = findClosingQuote(json, startQuote + 1)
            if (endQuote == -1) return null
            return json.substring(startQuote + 1, endQuote)
        }

        /** Extract an integer value for a given key. */
        fun extractInt(json: String, key: String): Int? {
            val keyIndex = json.indexOf(key)
            if (keyIndex == -1) return null
            val colonIndex = json.indexOf(':', keyIndex + key.length)
            if (colonIndex == -1) return null
            val numStart = json.indexOfFirst(colonIndex + 1) { it.isDigit() || it == '-' }
            if (numStart == -1) return null
            val numEnd = json.indexOfFirst(numStart + 1) { !it.isDigit() && it != '-' }
            val numStr = if (numEnd == -1) json.substring(numStart) else json.substring(numStart, numEnd)
            return numStr.toIntOrNull()
        }

        /** Extract a nested JSON object for a given key. */
        fun extractObject(json: String, key: String): String? {
            val keyIndex = json.indexOf(key)
            if (keyIndex == -1) return null
            val braceStart = json.indexOf('{', keyIndex + key.length)
            if (braceStart == -1) return null
            var depth = 0
            for (i in braceStart until json.length) {
                when (json[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return json.substring(braceStart, i + 1)
                    }
                }
            }
            return null
        }

        /** Extract a nested JSON array for a given key. */
        fun extractArray(json: String, key: String): String? {
            val keyIndex = json.indexOf(key)
            if (keyIndex == -1) return null
            val bracketStart = json.indexOf('[', keyIndex + key.length)
            if (bracketStart == -1) return null
            var depth = 0
            for (i in bracketStart until json.length) {
                when (json[i]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return json.substring(bracketStart, i + 1)
                    }
                }
            }
            return null
        }

        /** Find the closing quote, handling escape sequences. */
        private fun findClosingQuote(json: String, startIndex: Int): Int {
            var i = startIndex
            while (i < json.length) {
                when (json[i]) {
                    '\\' -> i += 2 // Skip escaped character
                    '"' -> return i
                    else -> i++
                }
            }
            return -1
        }

        /** Find first index matching predicate starting from given index. */
        private fun String.indexOfFirst(startIndex: Int, predicate: (Char) -> Boolean): Int {
            for (i in startIndex until length) {
                if (predicate(this[i])) return i
            }
            return -1
        }

        /** Unescape basic JSON string escapes. */
        fun unescapeJson(s: String): String = s
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}
