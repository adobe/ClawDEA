package com.adobe.clawdea.chat.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CodexSessionScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rollout(dir: File, name: String, cwd: String, sessionId: String, vararg lines: String): File {
        dir.mkdirs()
        val meta = """{"type":"session_meta","payload":{"session_id":"$sessionId","cwd":"$cwd","timestamp":"2026-07-14T15:06:28.038Z"}}"""
        val f = File(dir, name)
        f.writeText((listOf(meta) + lines).joinToString("\n"))
        return f
    }

    private fun userItem(vararg texts: String): String {
        val content = texts.joinToString(",") { """{"type":"input_text","text":${quote(it)}}""" }
        return """{"type":"response_item","payload":{"type":"message","role":"user","content":[$content]}}"""
    }

    private fun assistantItem(text: String) =
        """{"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":${quote(text)}}]}}"""

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun `scan returns codex sessions matching the project cwd, newest first`() {
        val root = tmp.newFolder("sessions")
        val day = File(root, "2026/07/14")
        rollout(day, "rollout-a-uuid-a.jsonl", cwd = "/proj", sessionId = "uuid-a", userItem("first task"))
        rollout(day, "rollout-b-uuid-b.jsonl", cwd = "/other", sessionId = "uuid-b", userItem("nope"))

        val sessions = CodexSessionScanner.scanIn(root, "/proj")
        assertEquals(1, sessions.size)
        assertEquals("uuid-a", sessions[0].id)
        assertEquals(SessionOrigin.CODEX, sessions[0].origin)
        assertEquals("first task", sessions[0].firstMessage)
    }

    @Test
    fun `scan skips injected environment_context as the first message`() {
        val root = tmp.newFolder("sessions")
        val day = File(root, "2026/07/14")
        rollout(
            day, "rollout-c-uuid-c.jsonl", cwd = "/proj", sessionId = "uuid-c",
            userItem("<environment_context>\n  <cwd>/proj</cwd>\n</environment_context>"),
            userItem("the real first prompt"),
        )
        val sessions = CodexSessionScanner.scanIn(root, "/proj")
        assertEquals("the real first prompt", sessions.single().firstMessage)
    }

    @Test
    fun `loadHistory returns user and assistant text, skipping developer and env context`() {
        val root = tmp.newFolder("sessions")
        val day = File(root, "2026/07/14")
        val f = rollout(
            day, "rollout-d-uuid-d.jsonl", cwd = "/proj", sessionId = "uuid-d",
            """{"type":"response_item","payload":{"type":"message","role":"developer","content":[{"type":"input_text","text":"system stuff"}]}}""",
            userItem("<environment_context><cwd>/proj</cwd></environment_context>"),
            userItem("what model are you?"),
            assistantItem("I'm Codex."),
        )
        val history = CodexSessionScanner.loadHistoryFromFile(f)
        assertEquals(2, history.size)
        assertTrue(history[0] is HistoryEntry.UserMessage)
        assertEquals("what model are you?", (history[0] as HistoryEntry.UserMessage).text)
        assertEquals("I'm Codex.", (history[1] as HistoryEntry.AssistantText).text)
    }

    @Test
    fun `loadHistory strips codex plugin recommendations and environment from a combined user item`() {
        val root = tmp.newFolder("sessions")
        val day = File(root, "2026/07/14")
        val f = rollout(
            day, "rollout-g-uuid-g.jsonl", cwd = "/proj", sessionId = "uuid-g",
            userItem(
                "<recommended_plugins>\n- SharePoint\n</recommended_plugins>",
                "<environment_context>\n  <cwd>/proj</cwd>\n</environment_context>",
                "the real user prompt",
            ),
        )

        val history = CodexSessionScanner.loadHistoryFromFile(f)

        assertEquals(1, history.size)
        assertEquals("the real user prompt", (history.single() as HistoryEntry.UserMessage).text)
    }

    @Test
    fun `loadHistory unwraps the ClawDEA first-turn preamble to the real user request`() {
        val root = tmp.newFolder("sessions")
        val day = File(root, "2026/07/14")
        val f = rollout(
            day, "rollout-e-uuid-e.jsonl", cwd = "/proj", sessionId = "uuid-e",
            userItem("<tooling>lots of preamble</tooling>\n\n---\n\nUser request:\nfix the bug"),
        )
        val history = CodexSessionScanner.loadHistoryFromFile(f)
        assertEquals("fix the bug", (history.single() as HistoryEntry.UserMessage).text)
    }

    @Test
    fun `findRolloutFile matches on id and cwd`() {
        val root = tmp.newFolder("sessions")
        val day = File(root, "2026/07/14")
        rollout(day, "rollout-f-uuid-f.jsonl", cwd = "/proj", sessionId = "uuid-f", userItem("hi"))

        assertTrue(CodexSessionScanner.findRolloutFile(root, "/proj", "uuid-f") != null)
        assertNull(CodexSessionScanner.findRolloutFile(root, "/wrong-cwd", "uuid-f"))
        assertNull(CodexSessionScanner.findRolloutFile(root, "/proj", "uuid-missing"))
    }
}
