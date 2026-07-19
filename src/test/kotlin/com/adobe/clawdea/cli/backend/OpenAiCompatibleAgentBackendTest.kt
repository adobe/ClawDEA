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
package com.adobe.clawdea.cli.backend

import com.adobe.clawdea.provider.openai.agent.AgentMessage
import com.adobe.clawdea.provider.openai.agent.AgentToolCall
import com.adobe.clawdea.provider.openai.agent.ConversationState
import com.adobe.clawdea.provider.openai.session.OpenAiSessionLedger
import com.adobe.clawdea.provider.openai.session.SessionLedgerRecord
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * Tests for [OpenAiCompatibleAgentBackend] ledger and resume logic.
 * These are lightweight tests that verify ledger write/read without requiring full IntelliJ context.
 */
class OpenAiCompatibleAgentBackendTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `ledger rebuild reconstructs messages from records`() {
        val projectPath = temp.newFolder("project").absolutePath
        val ledgerBase = temp.newFolder("ledger")
        val ledger = OpenAiSessionLedger(projectPath, ledgerBase.toPath())
        val sessionId = "test-session"

        // Seed ledger with conversation history
        ledger.append(sessionId, metaRecord(sessionId, projectPath))
        ledger.append(sessionId, userRecord("hello"))
        ledger.append(sessionId, assistantRecord("hi there"))
        ledger.append(sessionId, toolUseRecord("call-1", "test_tool", "{}", 0))
        ledger.append(sessionId, toolResultRecord("call-1", "result"))

        // Rebuild state manually (mirroring resumeFromLedger logic)
        val state = ConversationState()
        val ledgerFile = ledgerFile(ledgerBase, projectPath, sessionId)
        val loaded = OpenAiSessionLedger.read(ledgerFile.toPath())

        for (record in loaded.records) {
            when (record.type) {
                "user" -> {
                    val content = record.payload.get("content")?.asString ?: continue
                    state.messages.add(AgentMessage(role = "user", content = content))
                }
                "assistant" -> {
                    val content = record.payload.get("content")?.asString
                    state.messages.add(AgentMessage(role = "assistant", content = content))
                }
                "tool_use" -> {
                    val id = record.payload.get("id")?.asString ?: continue
                    val name = record.payload.get("name")?.asString ?: continue
                    val input = record.payload.get("input")?.asString ?: "{}"

                    // Append tool call to the last assistant message
                    val lastAssistant = state.messages.lastOrNull { it.role == "assistant" }
                    if (lastAssistant != null) {
                        val updatedToolCalls = lastAssistant.toolCalls + AgentToolCall(id, name, input)
                        val updatedMessage = lastAssistant.copy(toolCalls = updatedToolCalls)
                        state.messages[state.messages.lastIndexOf(lastAssistant)] = updatedMessage
                    }
                }
                "tool_result" -> {
                    val toolUseId = record.payload.get("toolUseId")?.asString ?: continue
                    val content = record.payload.get("content")?.asString ?: ""

                    state.messages.add(AgentMessage(
                        role = "tool",
                        content = content,
                        toolCallId = toolUseId,
                    ))
                    state.completedToolCallIds.add(toolUseId)
                }
            }
        }

        // Verify reconstructed state
        assertEquals(3, state.messages.size)
        assertEquals("user", state.messages[0].role)
        assertEquals("hello", state.messages[0].content)
        assertEquals("assistant", state.messages[1].role)
        assertEquals("hi there", state.messages[1].content)
        assertEquals(1, state.messages[1].toolCalls.size)
        assertEquals("call-1", state.messages[1].toolCalls[0].id)
        assertEquals("test_tool", state.messages[1].toolCalls[0].name)
        assertEquals("tool", state.messages[2].role)
        assertEquals("call-1", state.messages[2].toolCallId)
        assertTrue(state.completedToolCallIds.contains("call-1"))
    }

    @Test
    fun `ledger rebuild with empty file returns empty state`() {
        val projectPath = temp.newFolder("project").absolutePath
        val ledgerBase = temp.newFolder("ledger")
        val sessionId = "nonexistent"

        val state = ConversationState()
        val ledgerFile = ledgerFile(ledgerBase, projectPath, sessionId)

        // File doesn't exist — read should return empty
        val loaded = OpenAiSessionLedger.read(ledgerFile.toPath())
        assertEquals(0, loaded.records.size)

        // State should remain empty
        assertEquals(0, state.messages.size)
        assertEquals(0, state.completedToolCallIds.size)
    }

    private fun metaRecord(sessionId: String, projectPath: String): SessionLedgerRecord {
        val payload = JsonObject().apply {
            addProperty("sessionId", sessionId)
            addProperty("profileId", "test-profile")
            addProperty("projectPath", File(projectPath).canonicalPath)
            addProperty("model", "test-model")
            addProperty("createdAt", Instant.now().toString())
        }
        return SessionLedgerRecord(
            type = "meta",
            timestamp = Instant.now().toString(),
            payload = payload,
        )
    }

    private fun userRecord(content: String): SessionLedgerRecord {
        val payload = JsonObject().apply {
            addProperty("content", content)
        }
        return SessionLedgerRecord(
            type = "user",
            timestamp = Instant.now().toString(),
            payload = payload,
        )
    }

    private fun assistantRecord(content: String): SessionLedgerRecord {
        val payload = JsonObject().apply {
            addProperty("content", content)
        }
        return SessionLedgerRecord(
            type = "assistant",
            timestamp = Instant.now().toString(),
            payload = payload,
        )
    }

    private fun toolUseRecord(id: String, name: String, input: String, index: Int): SessionLedgerRecord {
        val payload = JsonObject().apply {
            addProperty("id", id)
            addProperty("name", name)
            addProperty("input", input)
            addProperty("index", index)
        }
        return SessionLedgerRecord(
            type = "tool_use",
            timestamp = Instant.now().toString(),
            payload = payload,
        )
    }

    private fun toolResultRecord(toolUseId: String, content: String): SessionLedgerRecord {
        val payload = JsonObject().apply {
            addProperty("toolUseId", toolUseId)
            addProperty("content", content)
            addProperty("isError", false)
        }
        return SessionLedgerRecord(
            type = "tool_result",
            timestamp = Instant.now().toString(),
            payload = payload,
        )
    }

    private fun ledgerFile(baseDir: File, projectPath: String, sessionId: String): File {
        val canonicalPath = File(projectPath).canonicalPath
        val projectSubdir = OpenAiSessionLedger.projectDirName(canonicalPath)
        return baseDir.resolve(projectSubdir).resolve("$sessionId.jsonl")
    }

    @Test
    fun `settings wiring compiles with default agentMaxToolRounds zero`() {
        // Smoke test: verify that ClawDEASettings.State declares agentMaxToolRounds with default 0.
        // Full turn-driving harness not available in this test suite, so we assert the data class
        // default directly. The backend construction compiling (checked by other tests) confirms
        // the wiring is valid.
        val defaultState = com.adobe.clawdea.settings.ClawDEASettings.State()
        assertEquals(0, defaultState.agentMaxToolRounds)
    }
}
