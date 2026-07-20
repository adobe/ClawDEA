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

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class OpenAiSessionLedgerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `reader keeps valid records before a corrupt trailing line`() {
        val file = temp.newFile("session.jsonl")
        file.writeText(metaLine() + "\n" + userLine("hello") + "\n{broken")

        val loaded = OpenAiSessionLedger.read(file.toPath())

        assertEquals(2, loaded.records.size)
        assertTrue(loaded.trailingRecordCorrupt)
    }

    @Test
    fun `reader handles blank lines between records`() {
        val file = temp.newFile("session.jsonl")
        file.writeText(metaLine() + "\n\n" + userLine("hello") + "\n")

        val loaded = OpenAiSessionLedger.read(file.toPath())

        assertEquals(2, loaded.records.size)
        assertFalse(loaded.trailingRecordCorrupt)
    }

    @Test
    fun `reader returns empty list for non-existent file`() {
        val nonExistent = temp.root.toPath().resolve("does-not-exist.jsonl")

        val loaded = OpenAiSessionLedger.read(nonExistent)

        assertEquals(0, loaded.records.size)
        assertFalse(loaded.trailingRecordCorrupt)
    }

    @Test
    fun `reader stops at mid-file corruption and discards subsequent valid lines`() {
        val file = temp.newFile("session.jsonl")
        file.writeText(metaLine() + "\n{broken\n" + userLine("discarded"))

        val loaded = OpenAiSessionLedger.read(file.toPath())

        // Only the meta record before corruption; the valid user line after corruption is discarded
        assertEquals(1, loaded.records.size)
        assertEquals("meta", loaded.records[0].type)
        assertTrue(loaded.trailingRecordCorrupt)
    }

    @Test
    fun `append writes one line and flushes`() {
        val dir = temp.newFolder()
        val projectPath = "/Users/test/project"
        val ledger = OpenAiSessionLedger(projectPath, dir.toPath())
        val sessionId = "test-session"

        val record = SessionLedgerRecord(
            schemaVersion = 1,
            type = "user",
            timestamp = "2026-07-16T10:00:00Z",
            payload = JsonObject().apply { addProperty("content", "hello") }
        )

        ledger.append(sessionId, record)

        val projectSubdir = OpenAiSessionLedger.projectDirName(java.io.File(projectPath).canonicalPath)
        val file = dir.toPath().resolve(projectSubdir).resolve("$sessionId.jsonl")
        assertTrue(Files.exists(file))
        val lines = Files.readAllLines(file)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("\"type\":\"user\""))
    }

    @Test
    fun `append multiple records creates multiple lines`() {
        val dir = temp.newFolder()
        val projectPath = "/Users/test/project"
        val ledger = OpenAiSessionLedger(projectPath, dir.toPath())
        val sessionId = "test-session"

        val record1 = SessionLedgerRecord(
            type = "meta",
            timestamp = "2026-07-16T10:00:00Z",
            payload = JsonObject().apply { addProperty("sessionId", sessionId) }
        )
        val record2 = SessionLedgerRecord(
            type = "user",
            timestamp = "2026-07-16T10:00:01Z",
            payload = JsonObject().apply { addProperty("content", "hello") }
        )

        ledger.append(sessionId, record1)
        ledger.append(sessionId, record2)

        val projectSubdir = OpenAiSessionLedger.projectDirName(java.io.File(projectPath).canonicalPath)
        val file = dir.toPath().resolve(projectSubdir).resolve("$sessionId.jsonl")
        val lines = Files.readAllLines(file)
        assertEquals(2, lines.size)
    }

    private fun metaLine(): String {
        val payload = JsonObject().apply {
            addProperty("sessionId", "test-123")
            addProperty("profileId", "profile-a")
            addProperty("projectPath", "/Users/test/project")
            addProperty("model", "test-model")
            addProperty("createdAt", "2026-07-16T10:00:00Z")
        }
        return """{"schemaVersion":1,"type":"meta","timestamp":"2026-07-16T10:00:00Z","payload":${payload}}"""
    }

    private fun userLine(content: String): String {
        val payload = JsonObject().apply {
            addProperty("content", content)
        }
        return """{"schemaVersion":1,"type":"user","timestamp":"2026-07-16T10:00:01Z","payload":${payload}}"""
    }
}
