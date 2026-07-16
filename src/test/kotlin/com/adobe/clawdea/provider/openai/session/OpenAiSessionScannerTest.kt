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

import com.adobe.clawdea.chat.session.SessionOrigin
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OpenAiSessionScannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `scan returns sessions for matching project path`() {
        val root = temp.newFolder()
        val projectPath = "/Users/test/project"

        writeLedger(root, "session-1", projectPath, "profile-a", "hello")
        writeLedger(root, "session-2", projectPath, "profile-a", "world")

        val sessions = OpenAiSessionScanner.scanIn(root, projectPath)

        assertEquals(2, sessions.size)
        assertEquals(SessionOrigin.OPENAI_COMPATIBLE, sessions[0].origin)
        assertEquals("profile-a", sessions[0].profileId)
    }

    @Test
    fun `scan filters out sessions from other projects`() {
        val root = temp.newFolder()
        val projectPath = "/Users/test/project"

        writeLedger(root, "session-1", projectPath, "profile-a", "hello")
        writeLedger(root, "session-2", "/Users/test/other", "profile-a", "world")

        val sessions = OpenAiSessionScanner.scanIn(root, projectPath)

        assertEquals(1, sessions.size)
        assertEquals("session-1", sessions[0].id)
    }

    @Test
    fun `loadHistory parses user and assistant entries`() {
        val root = temp.newFolder()
        val projectPath = "/Users/test/project"
        val canonicalPath = java.io.File(projectPath).canonicalPath

        val projectSubdir = root.resolve(OpenAiSessionLedger.projectDirName(canonicalPath))
        projectSubdir.mkdirs()
        val file = projectSubdir.resolve("test-session.jsonl")
        val records = listOf(
            metaRecord("test-session", "profile-a", canonicalPath),
            userRecord("hello"),
            assistantRecord("hi there"),
        )
        file.writeText(records.joinToString("\n") { com.google.gson.Gson().toJson(it) })

        val history = OpenAiSessionScanner.loadHistoryFromFile(file)

        assertEquals(2, history.size)
        assertTrue(history[0] is com.adobe.clawdea.chat.session.HistoryEntry.UserMessage)
        assertTrue(history[1] is com.adobe.clawdea.chat.session.HistoryEntry.AssistantText)
    }

    @Test
    fun `scan returns empty list for non-existent root`() {
        val nonExistent = temp.root.toPath().resolve("does-not-exist").toFile()

        val sessions = OpenAiSessionScanner.scanIn(nonExistent, "/any/path")

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `providerLabel falls back to generic when profile removed`() {
        val root = temp.newFolder()
        val projectPath = "/Users/test/project"

        writeLedger(root, "session-1", projectPath, "removed-profile", "hello")

        val sessions = OpenAiSessionScanner.scanIn(root, projectPath)

        assertEquals(1, sessions.size)
        assertEquals("OpenAI-compatible", sessions[0].providerLabel)
    }

    private fun writeLedger(root: File, sessionId: String, projectPath: String, profileId: String, firstUserContent: String) {
        // Compute per-project subdir matching the production layout
        val canonicalPath = java.io.File(projectPath).canonicalPath
        val projectSubdir = root.resolve(OpenAiSessionLedger.projectDirName(canonicalPath))
        projectSubdir.mkdirs()
        val file = projectSubdir.resolve("$sessionId.jsonl")
        val records = listOf(
            metaRecord(sessionId, profileId, canonicalPath),
            userRecord(firstUserContent),
        )
        file.writeText(records.joinToString("\n") { com.google.gson.Gson().toJson(it) })
    }

    private fun metaRecord(sessionId: String, profileId: String, projectPath: String): SessionLedgerRecord {
        val payload = JsonObject().apply {
            addProperty("sessionId", sessionId)
            addProperty("profileId", profileId)
            addProperty("projectPath", projectPath)
            addProperty("model", "test-model")
            addProperty("createdAt", "2026-07-16T10:00:00Z")
        }
        return SessionLedgerRecord(
            type = "meta",
            timestamp = "2026-07-16T10:00:00Z",
            payload = payload
        )
    }

    private fun userRecord(content: String): SessionLedgerRecord {
        val payload = JsonObject().apply {
            addProperty("content", content)
        }
        return SessionLedgerRecord(
            type = "user",
            timestamp = "2026-07-16T10:00:01Z",
            payload = payload
        )
    }

    private fun assistantRecord(content: String): SessionLedgerRecord {
        val payload = JsonObject().apply {
            addProperty("content", content)
        }
        return SessionLedgerRecord(
            type = "assistant",
            timestamp = "2026-07-16T10:00:02Z",
            payload = payload
        )
    }
}
