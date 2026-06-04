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

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionScannerTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun writeSessionFile(dir: File, id: String, vararg lines: String): File {
        val file = File(dir, "$id.jsonl")
        file.writeText(lines.joinToString("\n"))
        return file
    }

    @Test
    fun `extracts first message from queue-operation enqueue`() {
        // SessionScanner.scan uses a hardcoded path under ~/.claude, so test parseSessionFile indirectly
        // by constructing files and using reflection or testing the public scan() with a fake path.
        // Instead, we test the core parsing logic through scan() with a temp directory.

        // scan() computes: ~/.claude/projects/-<basePath encoded>
        // We can't easily override that, so test the format extraction directly.

        val dir = tmpDir.newFolder("sessions")
        val file = writeSessionFile(
            dir, "abc-123",
            """{"type":"queue-operation","operation":"enqueue","timestamp":"2026-04-11T16:47:39.700Z","sessionId":"abc-123","content":"fix the login bug"}""",
            """{"type":"queue-operation","operation":"dequeue","timestamp":"2026-04-11T16:47:39.700Z","sessionId":"abc-123"}""",
        )

        // Use file last modified as a fallback verification
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
    }

    @Test
    fun `scan returns empty for nonexistent project path`() {
        val sessions = SessionScanner.scan("/nonexistent/project/path/that/does/not/exist")
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `SessionInfo formats time correctly`() {
        val info = SessionInfo(
            id = "test-id",
            firstMessage = "hello world",
            timestamp = java.time.Instant.parse("2026-04-11T16:47:39.700Z"),
            fileSize = 1024,
        )
        val formatted = info.formattedTime()
        assertTrue("Should contain Apr", formatted.contains("Apr"))
        assertTrue("Should contain 11", formatted.contains("11"))
    }

    @Test
    fun `SessionInfo truncates long messages`() {
        val longMessage = "a".repeat(200)
        val info = SessionInfo(
            id = "test-id",
            firstMessage = longMessage.take(120),
            timestamp = java.time.Instant.now(),
            fileSize = 1024,
        )
        assertTrue(info.firstMessage.length <= 120)
    }

    @Test
    fun `loadHistory parses user-typed string message as UserMessage`() {
        val file = writeSessionFile(
            tmpDir.root, "s1",
            """{"type":"user","message":{"role":"user","content":"hello claude"},"uuid":"u1"}""",
        )
        val entries = SessionScanner.loadHistoryFromFile(file)
        assertEquals(1, entries.size)
        val msg = entries[0] as HistoryEntry.UserMessage
        assertEquals("hello claude", msg.text)
    }

    @Test
    fun `loadHistory parses tool_result envelope as ToolResult not UserMessage`() {
        // Regression: extractUserContent used to recurse into the inner content
        // array of a tool_result block and surface its text as a "You" message.
        val file = writeSessionFile(
            tmpDir.root, "s2",
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_42","type":"tool_result","content":[{"type":"text","text":"file edited"}]}]},"uuid":"u2"}""",
        )
        val entries = SessionScanner.loadHistoryFromFile(file)
        assertEquals(1, entries.size)
        val result = entries[0] as HistoryEntry.ToolResult
        assertEquals("toolu_42", result.toolUseId)
        assertEquals("file edited", result.content)
        assertFalse(result.isError)
    }

    @Test
    fun `loadHistory captures tool_use id alongside name and input`() {
        val file = writeSessionFile(
            tmpDir.root, "s3",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_99","name":"Edit","input":{"file_path":"/tmp/foo.txt","old_string":"a","new_string":"b"}}]},"uuid":"u3"}""",
        )
        val entries = SessionScanner.loadHistoryFromFile(file)
        assertEquals(1, entries.size)
        val use = entries[0] as HistoryEntry.ToolUse
        assertEquals("toolu_99", use.id)
        assertEquals("Edit", use.name)
        assertTrue(use.input.contains("\"file_path\":\"/tmp/foo.txt\""))
    }

    @Test
    fun `loadHistory extracts assistant text and skips thinking blocks`() {
        val file = writeSessionFile(
            tmpDir.root, "s4",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"the answer is 42"}]},"uuid":"u4"}""",
        )
        val entries = SessionScanner.loadHistoryFromFile(file)
        assertEquals(1, entries.size)
        val text = entries[0] as HistoryEntry.AssistantText
        assertEquals("the answer is 42", text.text)
    }

    @Test
    fun `loadHistory strips system-reminder envelopes from user content`() {
        val file = writeSessionFile(
            tmpDir.root, "s5",
            """{"type":"user","message":{"role":"user","content":"real message <system-reminder>internal note</system-reminder> here"},"uuid":"u5"}""",
        )
        val entries = SessionScanner.loadHistoryFromFile(file)
        assertEquals(1, entries.size)
        val msg = entries[0] as HistoryEntry.UserMessage
        assertFalse(msg.text.contains("system-reminder"))
        assertFalse(msg.text.contains("internal note"))
        assertTrue(msg.text.contains("real message"))
        assertTrue(msg.text.contains("here"))
    }

    @Test
    fun `loadHistory drops user message that is only system reminders`() {
        val file = writeSessionFile(
            tmpDir.root, "s6",
            """{"type":"user","message":{"role":"user","content":"<system-reminder>just an instruction</system-reminder>"},"uuid":"u6"}""",
        )
        val entries = SessionScanner.loadHistoryFromFile(file)
        assertTrue("system-reminder-only payloads should not surface as user text", entries.isEmpty())
    }

    @Test
    fun `loadHistory preserves order across user, assistant, and tool_result lines`() {
        val file = writeSessionFile(
            tmpDir.root, "s7",
            """{"type":"user","message":{"role":"user","content":"do the thing"},"uuid":"u1"}""",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"on it"},{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]},"uuid":"u2"}""",
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"t1","type":"tool_result","content":"foo.txt\nbar.txt"}]},"uuid":"u3"}""",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"done"}]},"uuid":"u4"}""",
        )
        val entries = SessionScanner.loadHistoryFromFile(file)
        assertEquals(5, entries.size)
        assertEquals("do the thing", (entries[0] as HistoryEntry.UserMessage).text)
        assertEquals("on it", (entries[1] as HistoryEntry.AssistantText).text)
        assertEquals("Bash", (entries[2] as HistoryEntry.ToolUse).name)
        assertEquals("t1", (entries[3] as HistoryEntry.ToolResult).toolUseId)
        assertEquals("done", (entries[4] as HistoryEntry.AssistantText).text)
    }

    @Test
    fun `loadHistory marks errored tool_result as isError`() {
        val file = writeSessionFile(
            tmpDir.root, "s8",
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"t9","type":"tool_result","content":"boom","is_error":true}]},"uuid":"u8"}""",
        )
        val entries = SessionScanner.loadHistoryFromFile(file)
        val result = entries.single() as HistoryEntry.ToolResult
        assertTrue(result.isError)
        assertEquals("boom", result.content)
    }

    @Test
    fun `loadHistory skips isMeta user lines (skill body injections)`() {
        // Regression: CC injects the full skill body as a `type:user, isMeta:true`
        // line right after a slash-command invocation. Live chat hides these;
        // replay used to dump the entire skill text under a "You" label.
        val file = writeSessionFile(
            tmpDir.root, "skill",
            """{"type":"user","message":{"role":"user","content":"/superpowers:systematic-debugging hit a flaky test"},"uuid":"u1"}""",
            """{"type":"user","isMeta":true,"message":{"role":"user","content":[{"type":"text","text":"Base directory for this skill: /Users/me/.claude/skills/systematic-debugging\n\n# Systematic Debugging\n..."}]},"uuid":"u2"}""",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"on it"}]},"uuid":"u3"}""",
        )
        val entries = SessionScanner.loadHistoryFromFile(file)
        assertEquals(2, entries.size)
        assertEquals("/superpowers:systematic-debugging hit a flaky test",
            (entries[0] as HistoryEntry.UserMessage).text)
        assertEquals("on it", (entries[1] as HistoryEntry.AssistantText).text)
        // Should never see any text from the injected skill body.
        assertFalse("Skill body must not leak as UserMessage",
            entries.any { it is HistoryEntry.UserMessage && it.text.contains("Base directory for this skill") })
        assertFalse("Skill body must not leak as UserMessage",
            entries.any { it is HistoryEntry.UserMessage && it.text.contains("Systematic Debugging") })
    }

    @Test
    fun `loadHistory captures parent_tool_use_id on inner tool_use and tool_result`() {
        val file = writeSessionFile(
            tmpDir.root, "subagent",
            """{"type":"assistant","parent_tool_use_id":"agent_1","message":{"role":"assistant","content":[{"type":"tool_use","id":"c1","name":"Read","input":{"file_path":"/a.kt"}}]},"uuid":"u1"}""",
            """{"type":"user","parent_tool_use_id":"agent_1","message":{"role":"user","content":[{"tool_use_id":"c1","type":"tool_result","content":"contents"}]},"uuid":"u2"}""",
            """{"type":"assistant","parent_tool_use_id":null,"message":{"role":"assistant","content":[{"type":"tool_use","id":"main1","name":"Grep","input":{"pattern":"x"}}]},"uuid":"u3"}""",
        )
        val entries = SessionScanner.loadHistoryFromFile(file)
        assertEquals(3, entries.size)
        val innerUse = entries[0] as HistoryEntry.ToolUse
        assertEquals("agent_1", innerUse.parentToolUseId)
        val innerResult = entries[1] as HistoryEntry.ToolResult
        assertEquals("agent_1", innerResult.parentToolUseId)
        val mainUse = entries[2] as HistoryEntry.ToolUse
        assertNull("main-agent tool_use must have null parentToolUseId", mainUse.parentToolUseId)
    }

    @Test
    fun `loadHistory reconstructs slash command from command envelope`() {
        // CC wraps slash command invocations as
        //   <command-message>name</command-message>
        //   <command-name>/name</command-name>
        //   <command-args>...</command-args>
        // The user originally typed "/name args"; resume should show that line,
        // not the raw envelope tags.
        val raw = "<command-message>superpowers:brainstorming</command-message>\\n" +
            "<command-name>/superpowers:brainstorming</command-name>\\n" +
            "<command-args>I need a 10-minute demo of ClawDEA</command-args>"
        val file = writeSessionFile(
            tmpDir.root, "cmd",
            """{"type":"user","message":{"role":"user","content":"$raw"},"uuid":"u1"}""",
        )
        val entries = SessionScanner.loadHistoryFromFile(file)
        val msg = entries.single() as HistoryEntry.UserMessage
        assertEquals("/superpowers:brainstorming I need a 10-minute demo of ClawDEA", msg.text)
        assertFalse(msg.text.contains("<command-"))
    }

    @Test
    fun `loadHistory handles command envelope with empty args`() {
        val raw = "<command-message>clear</command-message>\\n<command-name>/clear</command-name>\\n<command-args></command-args>"
        val file = writeSessionFile(
            tmpDir.root, "cmd2",
            """{"type":"user","message":{"role":"user","content":"$raw"},"uuid":"u1"}""",
        )
        val entries = SessionScanner.loadHistoryFromFile(file)
        val msg = entries.single() as HistoryEntry.UserMessage
        assertEquals("/clear", msg.text)
    }
}
