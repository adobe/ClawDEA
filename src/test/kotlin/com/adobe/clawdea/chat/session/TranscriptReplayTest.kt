package com.adobe.clawdea.chat.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptReplayTest {

    @Test
    fun `serializes user and assistant text with a source label`() {
        val block = TranscriptReplay.serialize(
            listOf(
                HistoryEntry.UserMessage("hello"),
                HistoryEntry.AssistantText("hi there"),
            ),
            sourceLabel = "Claude",
        )
        assertTrue(block.contains("<prior_conversation source=\"Claude\">"))
        assertTrue(block.contains("User: hello"))
        assertTrue(block.contains("Assistant: hi there"))
        assertTrue(block.trim().endsWith("</prior_conversation>"))
    }

    @Test
    fun `elides tool use and tool result (text-only fidelity)`() {
        val block = TranscriptReplay.serialize(
            listOf(
                HistoryEntry.UserMessage("run it"),
                HistoryEntry.ToolUse("id1", "Bash", "{\"cmd\":\"ls\"}"),
                HistoryEntry.ToolResult("id1", "a\nb", isError = false),
                HistoryEntry.AssistantText("done"),
            ),
            sourceLabel = "Codex",
        )
        assertFalse(block.contains("Bash"))
        assertFalse(block.contains("tool"))
        assertTrue(block.contains("User: run it"))
        assertTrue(block.contains("Assistant: done"))
    }

    @Test
    fun `empty when nothing carryable`() {
        assertEquals("", TranscriptReplay.serialize(emptyList(), "Claude"))
        assertEquals(
            "",
            TranscriptReplay.serialize(
                listOf(HistoryEntry.ToolUse("i", "n", "{}")),
                "Claude",
            ),
        )
    }

    @Test
    fun `drops oldest turns and marks truncation when over budget`() {
        val history = (1..50).flatMap {
            listOf(
                HistoryEntry.UserMessage("question number $it padded ".repeat(20)),
                HistoryEntry.AssistantText("answer number $it padded ".repeat(20)),
            )
        }
        val block = TranscriptReplay.serialize(history, "Claude", maxChars = 2_000)
        assertTrue(block.length <= 2_500) // budget + wrapper slack
        assertTrue(block.contains("[earlier turns omitted]"))
        // Most recent turn is retained; the very first is dropped.
        assertTrue(block.contains("question number 50"))
        assertFalse(block.contains("question number 1 padded"))
    }

    @Test
    fun `wrapFirstMessage prepends replay and preserves the user text`() {
        val wrapped = TranscriptReplay.wrapFirstMessage("<prior_conversation>x</prior_conversation>", "next thing")
        assertTrue(wrapped.startsWith("<prior_conversation>"))
        assertTrue(wrapped.endsWith("next thing"))
        assertTrue(wrapped.contains("Continue our conversation"))
    }

    @Test
    fun `wrapFirstMessage returns user text unchanged when no replay`() {
        assertEquals("just this", TranscriptReplay.wrapFirstMessage("", "just this"))
    }
}
