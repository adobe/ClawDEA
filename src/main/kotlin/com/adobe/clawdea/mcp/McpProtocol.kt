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
package com.adobe.clawdea.mcp

/**
 * JSON-RPC 2.0 helpers for the MCP Streamable HTTP transport.
 * Hand-rolled JSON to match the codebase pattern (no Gson dependency).
 */
object McpProtocol {

    // --- Request parsing ---

    fun parseJsonRpcMethod(json: String): String {
        return extractString(json, "\"method\"") ?: ""
    }

    fun parseJsonRpcId(json: String): String? {
        // id can be string or number; extract raw value
        val key = "\"id\""
        val keyIndex = json.indexOf(key)
        if (keyIndex == -1) return null
        val colonIndex = json.indexOf(':', keyIndex + key.length)
        if (colonIndex == -1) return null
        val afterColon = json.substring(colonIndex + 1).trimStart()
        return if (afterColon.startsWith("\"")) {
            // String id
            extractString(json, key)
        } else {
            // Numeric id
            afterColon.takeWhile { it.isDigit() || it == '-' }.ifEmpty { null }
        }
    }

    fun parseToolName(json: String): String {
        return extractNestedString(json, "\"params\"", "\"name\"") ?: ""
    }

    fun parseToolArguments(json: String): Map<String, String> {
        val argsObj = extractNestedObject(json, "\"params\"", "\"arguments\"") ?: return emptyMap()
        return parseSimpleObject(argsObj)
    }

    // --- Response building ---

    fun initializeResponse(id: String): String {
        return """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"clawdea-intellij","version":"1.0.0"}}}"""
    }

    fun toolsListResponse(id: String, toolsJson: String): String {
        return """{"jsonrpc":"2.0","id":$id,"result":{"tools":$toolsJson}}"""
    }

    fun toolResultResponse(id: String, text: String, isError: Boolean = false): String {
        val escaped = escapeJsonString(text)
        val errorField = if (isError) "\"isError\":true," else ""
        return """{"jsonrpc":"2.0","id":$id,"result":{${errorField}"content":[{"type":"text","text":"$escaped"}]}}"""
    }

    fun errorResponse(id: String?, code: Int, message: String): String {
        val escaped = escapeJsonString(message)
        val idVal = id ?: "null"
        return """{"jsonrpc":"2.0","id":$idVal,"error":{"code":$code,"message":"$escaped"}}"""
    }

    // Error codes
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // --- Tool schema builder ---

    fun toolSchema(
        name: String,
        description: String,
        properties: List<Triple<String, String, String>>, // name, type, description
        required: List<String> = emptyList(),
    ): String {
        val propsJson = properties.joinToString(",") { (pName, pType, pDesc) ->
            val escapedDesc = escapeJsonString(pDesc)
            "\"$pName\":{\"type\":\"$pType\",\"description\":\"$escapedDesc\"}"
        }
        val reqJson = if (required.isNotEmpty()) {
            ",\"required\":[${required.joinToString(",") { "\"$it\"" }}]"
        } else ""
        val escapedName = escapeJsonString(name)
        val escapedDesc = escapeJsonString(description)
        return "{\"name\":\"$escapedName\",\"description\":\"$escapedDesc\",\"inputSchema\":{\"type\":\"object\",\"properties\":{$propsJson}$reqJson}}"
    }

    // --- Internal helpers ---

    fun escapeJsonString(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
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
        return extractString(json.substring(outerIndex), innerKey)
    }

    private fun extractNestedObject(json: String, outerKey: String, innerKey: String): String? {
        val outerIndex = json.indexOf(outerKey)
        if (outerIndex == -1) return null
        val remainder = json.substring(outerIndex)
        val innerIndex = remainder.indexOf(innerKey)
        if (innerIndex == -1) return null
        val colonIndex = remainder.indexOf(':', innerIndex + innerKey.length)
        if (colonIndex == -1) return null
        val afterColon = remainder.substring(colonIndex + 1).trimStart()
        if (afterColon.isEmpty() || afterColon[0] != '{') return null
        // String-aware brace scan: a `}` inside a quoted value must not close
        // the outer object. Without this, arguments containing code snippets,
        // KDoc blocks, or shell commands would be truncated on the first `}`
        // they embed.
        var depth = 0
        var inString = false
        var i = 0
        while (i < afterColon.length) {
            val c = afterColon[i]
            if (inString) {
                if (c == '\\' && i + 1 < afterColon.length) { i += 2; continue }
                if (c == '"') inString = false
                i++
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return afterColon.substring(0, i + 1)
                    }
                }
                i++
            }
        }
        return null
    }

    /**
     * Parses a flat JSON object `{ "k": "v", "n": 42, "obj": { ... }, "arr": [ ... ] }`
     * into a `Map<String, String>` of its top-level keys.
     *
     * - String values are unescaped.
     * - Number / `true` / `false` / `null` values are captured as their raw
     *   textual form.
     * - Nested objects and arrays are preserved as their raw JSON text
     *   (including the surrounding `{}` or `[]`). This is required by the
     *   `request_permission` MCP tool whose `input` argument is a nested
     *   object, and is harmless for existing tools whose arguments are flat.
     */
    private fun parseSimpleObject(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val openIdx = json.indexOf('{')
        if (openIdx == -1) return result
        var i = openIdx + 1
        while (i < json.length) {
            // skip whitespace and commas
            while (i < json.length && (json[i].isWhitespace() || json[i] == ',')) i++
            if (i >= json.length || json[i] == '}') break
            // expect a quoted key
            if (json[i] != '"') return result
            val keyEnd = findStringEnd(json, i + 1) ?: return result
            val key = unescapeJsonString(json.substring(i + 1, keyEnd))
            i = keyEnd + 1
            // skip whitespace to colon
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != ':') return result
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length) return result
            val valueText: String
            when (json[i]) {
                '"' -> {
                    val end = findStringEnd(json, i + 1) ?: return result
                    valueText = unescapeJsonString(json.substring(i + 1, end))
                    i = end + 1
                }
                '{' -> {
                    val end = findBalancedEnd(json, i, '{', '}') ?: return result
                    valueText = json.substring(i, end + 1)
                    i = end + 1
                }
                '[' -> {
                    val end = findBalancedEnd(json, i, '[', ']') ?: return result
                    valueText = json.substring(i, end + 1)
                    i = end + 1
                }
                else -> {
                    // number, true, false, null — read until next comma, close brace, or whitespace
                    val start = i
                    while (i < json.length && json[i] != ',' && json[i] != '}' && !json[i].isWhitespace()) i++
                    valueText = json.substring(start, i)
                }
            }
            result[key] = valueText
        }
        return result
    }

    private fun findStringEnd(s: String, from: Int): Int? {
        var i = from
        while (i < s.length) {
            when {
                s[i] == '\\' && i + 1 < s.length -> i += 2
                s[i] == '"' -> return i
                else -> i++
            }
        }
        return null
    }

    private fun findBalancedEnd(s: String, from: Int, open: Char, close: Char): Int? {
        var depth = 0
        var i = from
        var inString = false
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                if (c == '\\' && i + 1 < s.length) { i += 2; continue }
                if (c == '"') inString = false
                i++
            } else {
                when (c) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
        }
        return null
    }

    fun unescapeJsonString(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '/' -> sb.append('/')
                    else -> { sb.append('\\'); sb.append(s[i + 1]) }
                }
                i += 2
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }
}
