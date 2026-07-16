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
package com.adobe.clawdea.provider.openai.session

import com.adobe.clawdea.chat.session.HistoryEntry
import com.adobe.clawdea.chat.session.SessionInfo
import com.adobe.clawdea.chat.session.SessionOrigin
import com.adobe.clawdea.provider.openai.profile.ProfileStore
import com.adobe.clawdea.settings.ClawDEASettings
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * Scans OpenAI-compatible session ledgers to expose them alongside Claude/Codex sessions in the
 * resume picker and to replay their transcripts when handing off across backends.
 *
 * Layout: ledgers live under `~/.clawdea/sessions/<sha256(project-path)>/<session-id>.jsonl`.
 * Each ledger is a JSONL file with records (see [SessionLedgerRecord]).
 */
object OpenAiSessionScanner {

    private fun sessionsRoot(): File {
        val home = System.getProperty("user.home")
        return File(home, ".clawdea/sessions")
    }

    fun scan(projectBasePath: String): List<SessionInfo> =
        scanIn(sessionsRoot(), projectBasePath)

    fun hasSession(projectBasePath: String, sessionId: String): Boolean {
        val projectSubdir = projectSubdir(projectBasePath)
        val file = projectSubdir.resolve("$sessionId.jsonl")
        if (!file.exists()) return false
        // Defensive check: meta projectPath should match
        val meta = parseMeta(file) ?: return false
        return meta.projectPath == File(projectBasePath).canonicalPath
    }

    fun loadHistory(projectBasePath: String, sessionId: String): List<HistoryEntry> {
        val projectSubdir = projectSubdir(projectBasePath)
        val file = projectSubdir.resolve("$sessionId.jsonl")
        return loadHistoryFromFile(file)
    }

    fun profileIdFor(projectBasePath: String, sessionId: String): String? {
        val projectSubdir = projectSubdir(projectBasePath)
        val file = projectSubdir.resolve("$sessionId.jsonl")
        return parseMeta(file)?.profileId
    }

    private fun projectSubdir(projectBasePath: String): File {
        val canonicalPath = File(projectBasePath).canonicalPath
        val dirName = OpenAiSessionLedger.projectDirName(canonicalPath)
        return sessionsRoot().resolve(dirName)
    }

    /** Test seam: scan an arbitrary sessions root. */
    internal fun scanIn(root: File, projectBasePath: String): List<SessionInfo> {
        val canonicalPath = File(projectBasePath).canonicalPath
        val projectSubdir = root.resolve(OpenAiSessionLedger.projectDirName(canonicalPath))
        if (!projectSubdir.isDirectory) return emptyList()
        val ledgers = projectSubdir.listFiles { f -> f.extension == "jsonl" && f.isFile } ?: return emptyList()

        return ledgers.mapNotNull { parseSessionFile(it, canonicalPath) }
            .sortedByDescending { it.timestamp }
    }

    private fun parseSessionFile(file: File, projectBasePath: String): SessionInfo? {
        val meta = parseMeta(file) ?: return null
        if (meta.projectPath != projectBasePath) return null

        val firstMessage = firstUserMessage(file) ?: "(empty session)"
        val timestamp = meta.timestamp ?: Instant.ofEpochMilli(file.lastModified())

        // Resolve provider label from profile (production only; tests skip this)
        val providerLabel = try {
            val profileStore = ProfileStore(ClawDEASettings.getInstance())
            val profile = meta.profileId?.let { profileStore.profile(it) }
            profile?.name ?: "OpenAI-compatible"
        } catch (_: Exception) {
            // Test context or settings unavailable — fall back to generic label
            "OpenAI-compatible"
        }

        return SessionInfo(
            id = meta.sessionId,
            firstMessage = firstMessage.take(120),
            timestamp = timestamp,
            fileSize = file.length(),
            origin = SessionOrigin.OPENAI_COMPATIBLE,
            profileId = meta.profileId,
            providerLabel = providerLabel,
        )
    }

    private data class Meta(val sessionId: String, val profileId: String?, val projectPath: String, val timestamp: Instant?)

    private fun parseMeta(file: File): Meta? {
        val loaded = OpenAiSessionLedger.read(file.toPath())
        val metaRecord = loaded.records.firstOrNull { it.type == "meta" } ?: return null
        val payload = metaRecord.payload

        val sessionId = payload.get("sessionId")?.asString ?: return null
        val profileId = payload.get("profileId")?.asString
        val projectPath = payload.get("projectPath")?.asString ?: return null
        val timestamp = payload.get("createdAt")?.asString?.let { parseInstant(it) }

        return Meta(sessionId, profileId, projectPath, timestamp)
    }

    private fun firstUserMessage(file: File): String? {
        val loaded = OpenAiSessionLedger.read(file.toPath())
        return loaded.records
            .firstOrNull { it.type == "user" }
            ?.payload
            ?.get("content")
            ?.asString
    }

    internal fun loadHistoryFromFile(file: File): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        val loaded = OpenAiSessionLedger.read(file.toPath())
        val entries = mutableListOf<HistoryEntry>()

        for (record in loaded.records) {
            when (record.type) {
                "user" -> {
                    val content = record.payload.get("content")?.asString ?: continue
                    entries.add(HistoryEntry.UserMessage(content))
                }
                "assistant" -> {
                    val content = record.payload.get("content")?.asString ?: continue
                    entries.add(HistoryEntry.AssistantText(content))
                }
                "tool_use" -> {
                    val id = record.payload.get("id")?.asString ?: continue
                    val name = record.payload.get("name")?.asString ?: continue
                    val input = record.payload.get("input")?.toString() ?: "{}"
                    entries.add(HistoryEntry.ToolUse(id, name, input))
                }
                "tool_result" -> {
                    val toolUseId = record.payload.get("toolUseId")?.asString ?: continue
                    val content = record.payload.get("content")?.asString ?: ""
                    val isError = record.payload.get("isError")?.asBoolean ?: false
                    entries.add(HistoryEntry.ToolResult(toolUseId, content, isError))
                }
                // Skip meta, reasoning, usage — not user-visible in history
            }
        }

        return entries
    }

    private fun parseInstant(ts: String): Instant? = runCatching { Instant.parse(ts) }.getOrNull()

    /**
     * Delete all session ledgers for a given profile across all projects. Used by profile removal
     * cleanup (Step 6); called from the settings card. Scans all per-project subdirs under
     * ~/.clawdea/sessions/<sha256>/.
     */
    fun deleteSessionsForProfile(profileId: String) {
        val root = sessionsRoot()
        if (!root.isDirectory) return

        // Scan all per-project subdirs (each is a sha256 hash)
        val projectSubdirs = root.listFiles { f -> f.isDirectory } ?: return
        for (projectSubdir in projectSubdirs) {
            val ledgers = projectSubdir.listFiles { f -> f.extension == "jsonl" && f.isFile } ?: continue
            for (file in ledgers) {
                val meta = parseMeta(file) ?: continue
                if (meta.profileId == profileId) {
                    runCatching { Files.delete(file.toPath()) }
                }
            }
        }
    }
}
