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
package com.adobe.clawdea.chat.session

import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SessionInfo(
    val id: String,
    val firstMessage: String,
    val timestamp: Instant,
    val fileSize: Long,
) {
    fun formattedTime(): String {
        val formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
            .withZone(ZoneId.systemDefault())
        return formatter.format(timestamp)
    }
}

sealed class HistoryEntry {
    data class UserMessage(val text: String) : HistoryEntry()
    data class AssistantText(val text: String) : HistoryEntry()
    data class ToolUse(val name: String, val input: String) : HistoryEntry()
}

/**
 * Scans Claude Code session .jsonl files to extract session metadata.
 */
object SessionScanner {

    fun scan(projectBasePath: String): List<SessionInfo> {
        val encodedPath = "-" + projectBasePath.trimStart('/').replace("/", "-")
        val sessionDir = File(System.getProperty("user.home") + "/.claude/projects/" + encodedPath)
        if (!sessionDir.isDirectory) return emptyList()

        val files = sessionDir.listFiles { f -> f.extension == "jsonl" && !f.isDirectory }
            ?: return emptyList()

        return files.mapNotNull { file -> parseSessionFile(file) }
            .sortedByDescending { it.timestamp }
    }

    fun hasSessionFile(projectBasePath: String, sessionId: String): Boolean {
        val encodedPath = "-" + projectBasePath.trimStart('/').replace("/", "-")
        return File(System.getProperty("user.home") + "/.claude/projects/" + encodedPath + "/$sessionId.jsonl").exists()
    }

    fun loadHistory(projectBasePath: String, sessionId: String): List<HistoryEntry> {
        val encodedPath = "-" + projectBasePath.trimStart('/').replace("/", "-")
        val file = File(System.getProperty("user.home") + "/.claude/projects/" + encodedPath + "/$sessionId.jsonl")
        if (!file.exists()) return emptyList()

        val entries = mutableListOf<HistoryEntry>()
        try {
            BufferedReader(FileReader(file)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) {
                        // Use literal substring match — extractString would find
                        // the wrong nested "type" inside the "message" object.
                        when {
                            line.contains("\"type\":\"assistant\"") -> {
                                parseAssistantBlocks(line, entries)
                            }
                            line.contains("\"type\":\"user\"") -> {
                                val text = extractUserContent(line)
                                if (!text.isNullOrBlank()) {
                                    entries.add(HistoryEntry.UserMessage(text))
                                }
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) {
            // Best-effort: return whatever we parsed so far
        }
        return entries
    }

    private fun parseAssistantBlocks(json: String, entries: MutableList<HistoryEntry>) {
        // Find "content":[ in the message
        val messageIdx = json.indexOf("\"message\"")
        if (messageIdx == -1) return
        val contentArrayStart = json.indexOf("\"content\":[", messageIdx)
        if (contentArrayStart == -1) return

        val arrayStart = json.indexOf('[', contentArrayStart)
        if (arrayStart == -1) return

        // Walk through content blocks
        var pos = arrayStart + 1
        while (pos < json.length) {
            // Find next object
            val objStart = json.indexOf('{', pos)
            if (objStart == -1) break

            val objEnd = findMatchingBrace(json, objStart)
            if (objEnd == -1) break

            val block = json.substring(objStart, objEnd + 1)
            val blockType = extractString(block, "\"type\"")

            when (blockType) {
                "text" -> {
                    val text = extractString(block, "\"text\"")
                    if (!text.isNullOrBlank()) {
                        entries.add(HistoryEntry.AssistantText(text))
                    }
                }
                "tool_use" -> {
                    val name = extractString(block, "\"name\"") ?: ""
                    val input = extractObject(block, "\"input\"") ?: "{}"
                    entries.add(HistoryEntry.ToolUse(name, input))
                }
            }

            pos = objEnd + 1
        }
    }

    private fun findMatchingBrace(json: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until json.length) {
            val c = json[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun extractObject(json: String, key: String): String? {
        val keyIndex = json.indexOf(key)
        if (keyIndex == -1) return null
        val colonIndex = json.indexOf(':', keyIndex + key.length)
        if (colonIndex == -1) return null
        val afterColon = json.substring(colonIndex + 1).trimStart()
        if (afterColon.isEmpty() || afterColon[0] != '{') return null
        val end = findMatchingBrace(afterColon, 0)
        if (end == -1) return null
        return afterColon.substring(0, end + 1)
    }

    private fun parseSessionFile(file: File): SessionInfo? {
        val id = file.nameWithoutExtension
        val fileSize = file.length()
        var firstMessage: String? = null
        var timestamp: Instant? = null

        try {
            BufferedReader(FileReader(file)).use { reader ->
                var linesRead = 0
                while (linesRead < 30) {
                    val line = reader.readLine() ?: break
                    linesRead++
                    if (line.isBlank()) continue

                    val type = extractString(line, "\"type\"") ?: continue

                    // queue-operation enqueue has the original user prompt and timestamp
                    if (type == "queue-operation" && line.contains("\"enqueue\"")) {
                        if (firstMessage == null) {
                            firstMessage = extractString(line, "\"content\"")?.take(120)
                        }
                        if (timestamp == null) {
                            val ts = extractString(line, "\"timestamp\"")
                            timestamp = ts?.let { parseTimestamp(it) }
                        }
                    }

                    // Fallback: user message content
                    if (type == "user" && firstMessage == null) {
                        firstMessage = extractUserContent(line)?.take(120)
                    }

                    if (firstMessage != null && timestamp != null) break
                }
            }
        } catch (_: Exception) {
            return null
        }

        if (timestamp == null) {
            timestamp = Instant.ofEpochMilli(file.lastModified())
        }

        return SessionInfo(
            id = id,
            firstMessage = firstMessage ?: "(empty session)",
            timestamp = timestamp,
            fileSize = fileSize,
        )
    }

    private fun extractUserContent(json: String): String? {
        // Try "message":{"role":"user","content":"..."}
        val messageIdx = json.indexOf("\"message\"")
        if (messageIdx == -1) return null
        val contentIdx = json.indexOf("\"content\"", messageIdx)
        if (contentIdx == -1) return null

        val colonIdx = json.indexOf(':', contentIdx + 9)
        if (colonIdx == -1) return null
        val afterColon = json.substring(colonIdx + 1).trimStart()

        // String content
        if (afterColon.startsWith("\"")) {
            return extractString(json.substring(contentIdx), "\"content\"")
        }

        // Array content — find first text block
        if (afterColon.startsWith("[")) {
            val textIdx = afterColon.indexOf("\"type\":\"text\"")
            if (textIdx != -1) {
                return extractString(afterColon.substring(textIdx), "\"text\"")
            }
        }

        return null
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

    private fun parseTimestamp(ts: String): Instant? {
        return try {
            Instant.parse(ts)
        } catch (_: Exception) {
            null
        }
    }
}
