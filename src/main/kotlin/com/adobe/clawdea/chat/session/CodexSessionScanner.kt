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

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant

/**
 * Scans `codex` session rollouts to expose them alongside Claude sessions in the resume picker and
 * to replay their transcripts when handing off across backends. The codex peer of [SessionScanner].
 *
 * Layout: codex writes one rollout JSONL per session under a date tree
 * `~/.codex/sessions/YYYY/MM/DD/rollout-<ts>-<uuid>.jsonl`. Unlike Claude's per-project directory,
 * the tree is global, so we filter by the `cwd` recorded in each rollout's leading `session_meta`
 * line. The resumable id is `session_meta.payload.session_id` (what `codex exec resume <id>` takes).
 *
 * Line schema (see docs/superpowers/specs/2026-07-14-codex-interface-findings.md):
 *  - `session_meta`  — payload has `session_id`, `cwd`, `timestamp`.
 *  - `response_item` — payload is a chat item: `{role, type:"message", content:[{type,text}]}` for
 *    user/assistant turns; `reasoning`/`developer` items are internal and skipped.
 */
object CodexSessionScanner {

    private val CODEX_HOME: File
        get() = System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".codex")

    private fun sessionsRoot(): File = File(CODEX_HOME, "sessions")

    fun scan(projectBasePath: String): List<SessionInfo> =
        scanIn(sessionsRoot(), projectBasePath)

    fun hasSession(projectBasePath: String, sessionId: String): Boolean =
        findRolloutFile(sessionsRoot(), projectBasePath, sessionId) != null

    fun loadHistory(projectBasePath: String, sessionId: String): List<HistoryEntry> {
        val file = findRolloutFile(sessionsRoot(), projectBasePath, sessionId) ?: return emptyList()
        return loadHistoryFromFile(file)
    }

    /** Test seam: scan an arbitrary sessions root. */
    internal fun scanIn(root: File, projectBasePath: String): List<SessionInfo> {
        if (!root.isDirectory) return emptyList()
        val rollouts = root.walkTopDown().filter { it.isFile && it.extension == "jsonl" }
        return rollouts.mapNotNull { parseSessionFile(it, projectBasePath) }
            .sortedByDescending { it.timestamp }
            .toList()
    }

    /** Test seam: locate a rollout by id under an arbitrary root. */
    internal fun findRolloutFile(root: File, projectBasePath: String, sessionId: String): File? {
        if (sessionId.isBlank() || !root.isDirectory) return null
        // The uuid is embedded in the filename, so match on name first (cheap), then confirm cwd.
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "jsonl" && it.name.contains(sessionId) }
            .firstOrNull { f -> meta(f)?.let { m -> m.sessionId == sessionId && m.cwd == projectBasePath } == true }
    }

    private data class Meta(val sessionId: String, val cwd: String?, val timestamp: Instant?)

    private fun meta(file: File): Meta? {
        val firstLine = file.bufferedReader().use { it.readLine() } ?: return null
        return try {
            val obj = JsonParser.parseString(firstLine).asJsonObject
            if (obj.str("type") != "session_meta") return null
            val payload = obj.getAsJsonObject("payload") ?: return null
            val id = payload.str("session_id") ?: payload.str("id") ?: return null
            Meta(id, payload.str("cwd"), payload.str("timestamp")?.let(::parseInstant))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSessionFile(file: File, projectBasePath: String): SessionInfo? {
        val m = meta(file) ?: return null
        if (m.cwd != projectBasePath) return null
        val firstMessage = firstUserMessage(file) ?: "(empty session)"
        return SessionInfo(
            id = m.sessionId,
            firstMessage = firstMessage.take(120),
            timestamp = m.timestamp ?: Instant.ofEpochMilli(file.lastModified()),
            fileSize = file.length(),
            origin = SessionOrigin.CODEX,
        )
    }

    private fun firstUserMessage(file: File): String? {
        file.bufferedReader().use { reader ->
            var line = reader.readLine()
            var scanned = 0
            while (line != null && scanned < 200) {
                scanned++
                val text = userTextOf(line)
                if (!text.isNullOrBlank()) return text
                line = reader.readLine()
            }
        }
        return null
    }

    internal fun loadHistoryFromFile(file: File): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        val entries = mutableListOf<HistoryEntry>()
        try {
            file.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) parseLine(line, entries)
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) {
            // Best-effort — return whatever parsed.
        }
        return entries
    }

    private fun parseLine(json: String, entries: MutableList<HistoryEntry>) {
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        if (obj.str("type") != "response_item") return
        val payload = obj.getAsJsonObject("payload") ?: return
        if (payload.str("type") != "message") return
        val role = payload.str("role") ?: return
        val text = contentText(payload).trim()
        if (text.isEmpty()) return
        when (role) {
            "user" -> {
                val cleaned = cleanUserText(text)
                if (cleaned.isNotBlank()) entries.add(HistoryEntry.UserMessage(cleaned))
            }
            "assistant" -> entries.add(HistoryEntry.AssistantText(text))
            // developer/system/tool items are internal context — skip.
        }
    }

    /** Returns the visible user text for a response_item line, or null if it's not a real user turn. */
    private fun userTextOf(json: String): String? {
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        if (obj.str("type") != "response_item") return null
        val payload = obj.getAsJsonObject("payload") ?: return null
        if (payload.str("type") != "message" || payload.str("role") != "user") return null
        return cleanUserText(contentText(payload).trim()).takeIf { it.isNotBlank() }
    }

    /**
     * Strips codex/ClawDEA-injected context that isn't a real user prompt:
     *  - `<recommended_plugins>…</recommended_plugins>`, `<environment_context>…</environment_context>`,
     *    and the JetBrains harness note codex records as synthetic user content. Codex may put both
     *    XML blocks and the real prompt in separate content entries of the same `role:"user"` item;
     *    [contentText] joins those entries before this cleanup runs.
     *  - ClawDEA's own first-turn preamble ([com.adobe.clawdea.cli.CodexInstructions]), which wraps
     *    the real prompt after a `User request:` marker — keep only the part after the last marker.
     */
    private fun cleanUserText(raw: String): String {
        var body = raw.trim()
        while (true) {
            val tag = SYNTHETIC_USER_BLOCKS.firstOrNull { body.startsWith("<$it>") } ?: break
            val closingTag = "</$tag>"
            val end = body.indexOf(closingTag)
            if (end < 0) break
            body = body.substring(end + closingTag.length).trimStart()
        }
        if (body.startsWith("You are running inside JetBrains")) return ""
        val marker = "User request:\n"
        val idx = body.lastIndexOf(marker)
        return (if (idx >= 0) body.substring(idx + marker.length) else body).trim()
    }

    private fun contentText(payload: JsonObject): String {
        val content = payload.get("content") ?: return ""
        if (content.isJsonPrimitive) return content.asString
        if (!content.isJsonArray) return ""
        val sb = StringBuilder()
        for (el in content.asJsonArray) {
            val block = el as? JsonObject ?: continue
            block.str("text")?.let {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(it)
            }
        }
        return sb.toString()
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.getAsJsonObject(key: String): JsonObject? =
        get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun parseInstant(ts: String): Instant? = runCatching { Instant.parse(ts) }.getOrNull()

    private val SYNTHETIC_USER_BLOCKS = listOf("recommended_plugins", "environment_context")
}
