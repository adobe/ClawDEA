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
package com.adobe.clawdea.chat.permission

import com.adobe.clawdea.chat.MessageRenderer

/**
 * Formats a one-line human-readable summary of a tool call. Falls back to the
 * tool name when no meaningful field is available.
 */
object PermissionSummaryBuilder {

    private const val MAX_LEN = 120

    fun build(toolName: String, inputJson: String): String {
        if (inputJson.isBlank()) return toolName
        val raw = when (toolName) {
            "Bash" -> MessageRenderer.extractJsonString(inputJson, "command")
            "WebFetch" -> MessageRenderer.extractJsonString(inputJson, "url")
            "WebSearch" -> MessageRenderer.extractJsonString(inputJson, "query")
            "Edit", "Write", "MultiEdit" -> MessageRenderer.extractJsonString(inputJson, "file_path")
            "NotebookEdit" -> MessageRenderer.extractJsonString(inputJson, "notebook_path")
            else -> fallbackSummary(inputJson)
        } ?: return toolName
        return truncate(collapseWhitespace(raw))
    }

    private fun collapseWhitespace(s: String): String =
        s.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex(" {2,}"), " ").trim()

    private fun truncate(s: String): String =
        if (s.length <= MAX_LEN) s else s.substring(0, MAX_LEN) + "…"

    /**
     * For unknown tools: find the first two top-level string fields and render
     * them as "k1=v1, k2=v2". MessageRenderer.extractJsonString only reads a
     * given key, so we pull top-level keys with a light regex.
     */
    private fun fallbackSummary(json: String): String? {
        val keyRegex = Regex("\"(\\w+)\"\\s*:")
        val keys = keyRegex.findAll(json).map { it.groupValues[1] }.distinct().take(2).toList()
        if (keys.isEmpty()) return null
        val parts = keys.mapNotNull { key ->
            MessageRenderer.extractJsonString(json, key)?.let { "$key=$it" }
        }
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }
}
