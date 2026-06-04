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
    data class ToolUse(val id: String, val name: String, val input: String, val parentToolUseId: String? = null) : HistoryEntry()
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean, val parentToolUseId: String? = null) : HistoryEntry()
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
        return loadHistoryFromFile(file)
    }

    /**
     * Overload for tests — accepts an arbitrary file so fixtures can live
     * under a temp folder without colliding with the user's real
     * `~/.claude/projects/` cache. Production callers should use the
     * [loadHistory] overload that takes a project path + session id.
     */
    internal fun loadHistoryFromFile(file: File): List<HistoryEntry> {
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
                                parseUserBlocks(line, entries)
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
        val blocks = extractMessageContentBlocks(json) ?: return
        // Top-level envelope field linking inner events to their dispatching
        // sub-agent (`Agent`) tool_use. Absent / null on main-agent lines.
        val parentToolUseId = extractString(json, "\"parent_tool_use_id\"")
        for (block in blocks) {
            when (extractString(block, "\"type\"")) {
                "text" -> {
                    val text = extractString(block, "\"text\"")
                    if (!text.isNullOrBlank()) {
                        entries.add(HistoryEntry.AssistantText(text))
                    }
                }
                "tool_use" -> {
                    val id = extractString(block, "\"id\"") ?: ""
                    val name = extractString(block, "\"name\"") ?: ""
                    val input = extractObject(block, "\"input\"") ?: "{}"
                    entries.add(HistoryEntry.ToolUse(id, name, input, parentToolUseId))
                }
            }
            // Skip "thinking" blocks and anything else.
        }
    }

    /**
     * A `type:"user"` JSONL line can be one of:
     *  - A real user-typed message (`message.content` is a string)
     *  - A tool_result envelope CC writes back to the model (`message.content`
     *    is an array containing a `tool_result` block)
     *  - An array of `text`/`image` blocks (multimodal user input or
     *    system-reminder injections)
     *
     * Live rendering classifies these via [CliEventParser.parseUserMessage]:
     * tool_result blocks become a `ToolResult` event, everything else is
     * routed back to the user-input field. The previous implementation here
     * naively recursed into nested content arrays and pulled tool_result text
     * out as a user message — that's what made approved edits and sub-agent
     * output appear under a "You" label after `/resume`.
     */
    private fun parseUserBlocks(json: String, entries: MutableList<HistoryEntry>) {
        // `isMeta:true` lines are CC-injected synthetic prompts — skill
        // bodies, init prompts, hook-driven context dumps. The live chat
        // panel never renders these (they go straight to the model as
        // hidden context), so resume mustn't surface them under a "You"
        // label either. This guard is what suppresses the long
        // "Base directory for this skill: ..." text that used to follow
        // every `/superpowers:*` invocation in the replay.
        if (json.contains("\"isMeta\":true")) return

        // String content — the common case for user-typed prompts.
        val stringContent = extractStringContent(json)
        if (stringContent != null) {
            val cleaned = cleanUserText(stringContent)
            if (cleaned.isNotBlank()) {
                entries.add(HistoryEntry.UserMessage(cleaned))
            }
            return
        }

        // Array content — walk blocks and classify by type.
        val blocks = extractMessageContentBlocks(json) ?: return
        val parentToolUseId = extractString(json, "\"parent_tool_use_id\"")
        val userTextParts = mutableListOf<String>()
        for (block in blocks) {
            when (extractString(block, "\"type\"")) {
                "tool_result" -> {
                    val id = extractString(block, "\"tool_use_id\"") ?: ""
                    val content = extractToolResultContent(block)
                    val isError = block.contains("\"is_error\":true")
                    entries.add(HistoryEntry.ToolResult(id, content, isError, parentToolUseId))
                }
                "text" -> {
                    extractString(block, "\"text\"")?.let { userTextParts.add(it) }
                }
                // Skip "image" and other multimodal block types — there's no
                // first-class image rendering in the history view.
            }
        }
        if (userTextParts.isNotEmpty()) {
            val cleaned = cleanUserText(userTextParts.joinToString("\n\n"))
            if (cleaned.isNotBlank()) {
                entries.add(HistoryEntry.UserMessage(cleaned))
            }
        }
    }

    /**
     * Reconstruct the user-visible text from a raw `message.content` payload:
     *  - If it's a slash-command envelope, rebuild `<command-name> <args>`
     *    so the replay looks like the line the user actually typed.
     *  - Otherwise strip `<system-reminder>` / `<command-message>` style
     *    wrappers that the model emits as context but that should never
     *    appear under a "You" label.
     */
    private fun cleanUserText(raw: String): String {
        val invocation = extractSlashCommandInvocation(raw)
        if (invocation != null) return invocation
        return stripSystemReminders(raw)
    }

    private fun extractSlashCommandInvocation(text: String): String? {
        if (!text.contains("<command-")) return null
        val name = COMMAND_NAME_RE.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val args = COMMAND_ARGS_RE.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        // command-message echoes the human-readable command label
        // (e.g. "superpowers:brainstorming"). We prefer command-name
        // because that's the actual slash command the user typed.
        if (name.isEmpty() && args.isEmpty()) return null
        return listOf(name, args).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * Returns the raw substring of each top-level object in `message.content`
     * when it's an array, or null if `message.content` is a string / absent.
     */
    private fun extractMessageContentBlocks(json: String): List<String>? {
        val messageIdx = json.indexOf("\"message\"")
        if (messageIdx == -1) return null
        val contentArrayStart = json.indexOf("\"content\":[", messageIdx)
        if (contentArrayStart == -1) return null
        val arrayStart = json.indexOf('[', contentArrayStart)
        if (arrayStart == -1) return null
        val arrayEnd = findMatchingBracket(json, arrayStart)
        if (arrayEnd == -1) return null

        val blocks = mutableListOf<String>()
        var i = arrayStart + 1
        while (i < arrayEnd) {
            val c = json[i]
            if (c == '{') {
                val end = findMatchingBrace(json, i)
                if (end == -1 || end > arrayEnd) break
                blocks.add(json.substring(i, end + 1))
                i = end + 1
            } else {
                i++
            }
        }
        return blocks
    }

    /** Returns the value of `message.content` when it's a JSON string. */
    private fun extractStringContent(json: String): String? {
        val messageIdx = json.indexOf("\"message\"")
        if (messageIdx == -1) return null
        val contentKey = json.indexOf("\"content\"", messageIdx)
        if (contentKey == -1) return null
        val colon = json.indexOf(':', contentKey + 9)
        if (colon == -1) return null
        val afterColon = json.substring(colon + 1).trimStart()
        if (afterColon.isEmpty() || afterColon[0] != '"') return null
        return extractString(json.substring(contentKey), "\"content\"")
    }

    /**
     * Extract the textual content of a tool_result block. The block's
     * `content` field is either a string or an array of `text`/`image`
     * blocks — concatenate the text portions.
     */
    private fun extractToolResultContent(block: String): String {
        val contentIdx = block.indexOf("\"content\"")
        if (contentIdx == -1) return ""
        val colon = block.indexOf(':', contentIdx + 9)
        if (colon == -1) return ""
        val afterColon = block.substring(colon + 1).trimStart()
        if (afterColon.isEmpty()) return ""

        // String content
        if (afterColon[0] == '"') {
            return extractString(block.substring(contentIdx), "\"content\"") ?: ""
        }

        // Array content — pull every text block
        if (afterColon[0] == '[') {
            val arrayStart = block.indexOf('[', colon)
            val arrayEnd = findMatchingBracket(block, arrayStart)
            if (arrayEnd == -1) return ""
            val parts = mutableListOf<String>()
            var i = arrayStart + 1
            while (i < arrayEnd) {
                val c = block[i]
                if (c == '{') {
                    val end = findMatchingBrace(block, i)
                    if (end == -1 || end > arrayEnd) break
                    val inner = block.substring(i, end + 1)
                    if (extractString(inner, "\"type\"") == "text") {
                        extractString(inner, "\"text\"")?.let { parts.add(it) }
                    }
                    i = end + 1
                } else {
                    i++
                }
            }
            return parts.joinToString("\n")
        }

        return ""
    }

    /**
     * Strip `<system-reminder>` and `<command-message>` envelopes that CC
     * splices into user content. Those are agent-only instructions, not text
     * the user actually typed, so they shouldn't appear under the "You"
     * label after `/resume`.
     */
    private fun stripSystemReminders(text: String): String {
        if (!text.contains('<')) return text.trim()
        var out = text
        for (tag in SYSTEM_TAGS) {
            val open = "<$tag>"
            val close = "</$tag>"
            while (true) {
                val start = out.indexOf(open)
                if (start == -1) break
                val end = out.indexOf(close, start)
                if (end == -1) break
                out = out.substring(0, start) + out.substring(end + close.length)
            }
        }
        return out.trim()
    }

    private fun findMatchingBracket(json: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until json.length) {
            val c = json[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\' && inString) { escaped = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
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

    /**
     * Fallback extractor for [parseSessionFile] — accepts string or
     * text-array content. Mirrors what we'd want to show in a session
     * picker, so tool_result envelopes are intentionally ignored.
     */
    private fun extractUserContent(json: String): String? {
        val messageIdx = json.indexOf("\"message\"")
        if (messageIdx == -1) return null
        val contentIdx = json.indexOf("\"content\"", messageIdx)
        if (contentIdx == -1) return null
        val colonIdx = json.indexOf(':', contentIdx + 9)
        if (colonIdx == -1) return null
        val afterColon = json.substring(colonIdx + 1).trimStart()

        if (afterColon.startsWith("\"")) {
            return extractString(json.substring(contentIdx), "\"content\"")
        }
        if (afterColon.startsWith("[")) {
            // Skip tool_result wrappers — they aren't user-typed text.
            if (afterColon.contains("\"type\":\"tool_result\"")) return null
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

    // Note: `command-args` is intentionally not in this list — slash-command
    // envelopes are unwrapped via [extractSlashCommandInvocation] which
    // preserves the args text. Stripping it here would silently drop the
    // user's actual input.
    private val SYSTEM_TAGS = listOf("system-reminder", "command-message", "command-name")

    private val COMMAND_NAME_RE = Regex("<command-name>([\\s\\S]*?)</command-name>")
    private val COMMAND_ARGS_RE = Regex("<command-args>([\\s\\S]*?)</command-args>")
}
