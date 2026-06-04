/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.chat.session

import com.adobe.clawdea.chat.MessageRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryReplayRendererTest {

    private val renderer = MessageRenderer()

    @Test
    fun `passthrough renders user, assistant, and a single tool block`() {
        val history = listOf(
            HistoryEntry.UserMessage("hello"),
            HistoryEntry.AssistantText("on it"),
            HistoryEntry.ToolUse("t1", "Read", """{"file_path":"/a.kt"}"""),
            HistoryEntry.ToolResult("t1", "file contents", false),
        )
        val out = HistoryReplayRenderer.render(history, renderer)
        assertEquals(3, out.size)
        assertTrue(out[0].contains("You"))
        assertTrue(out[1].contains("Claude"))
        // The Read tool renders as a file link; ToolResult does not emit its own entry.
        assertTrue(out[2].contains("a.kt"))
    }

    @Test
    fun `sub-agent dispatch folds children into one collapsed card`() {
        val history = listOf(
            HistoryEntry.ToolUse(
                "agent_1", "Agent",
                """{"subagent_type":"wiki-librarian","description":"Research the chat UI"}""",
            ),
            HistoryEntry.ToolUse("c1", "Read", """{"file_path":"/a.kt"}""", parentToolUseId = "agent_1"),
            HistoryEntry.ToolResult("c1", "file contents", false, parentToolUseId = "agent_1"),
            HistoryEntry.ToolUse("c2", "Grep", """{"pattern":"foo"}""", parentToolUseId = "agent_1"),
            HistoryEntry.ToolResult("c2", "hits", false, parentToolUseId = "agent_1"),
            HistoryEntry.ToolResult("agent_1", "final summary", false),
        )
        val out = HistoryReplayRenderer.render(history, renderer)
        assertEquals("children must be folded into a single card", 1, out.size)
        val card = out[0]
        assertTrue(card.contains("subagent-block"))
        assertTrue(card.contains("""data-tool-id="c1""""))
        assertTrue(card.contains("""data-tool-id="c2""""))
        assertTrue(card.contains("2 steps"))
        assertTrue(card.contains("wiki-librarian"))
        assertTrue(card.contains("final summary"))
        assertTrue("finished card must not be expanded", !card.contains("expanded"))
    }

    @Test
    fun `sub-agent without its own result renders as aborted`() {
        val history = listOf(
            HistoryEntry.ToolUse(
                "agent_1", "Agent",
                """{"subagent_type":"wiki-librarian","description":"Research"}""",
            ),
            HistoryEntry.ToolUse("c1", "Read", """{"file_path":"/a.kt"}""", parentToolUseId = "agent_1"),
            HistoryEntry.ToolResult("c1", "file contents", false, parentToolUseId = "agent_1"),
        )
        val out = HistoryReplayRenderer.render(history, renderer)
        assertEquals(1, out.size)
        assertTrue(out[0].contains("subagent-summary-aborted"))
    }

    @Test
    fun `parallel agents each get their own card with the right children`() {
        val history = listOf(
            HistoryEntry.ToolUse("agent_a", "Agent", """{"subagent_type":"alpha","description":"A"}"""),
            HistoryEntry.ToolUse("agent_b", "Agent", """{"subagent_type":"beta","description":"B"}"""),
            HistoryEntry.ToolUse("a1", "Read", """{"file_path":"/a.kt"}""", parentToolUseId = "agent_a"),
            HistoryEntry.ToolResult("a1", "a-out", false, parentToolUseId = "agent_a"),
            HistoryEntry.ToolUse("b1", "Grep", """{"pattern":"x"}""", parentToolUseId = "agent_b"),
            HistoryEntry.ToolResult("b1", "b-out", false, parentToolUseId = "agent_b"),
            HistoryEntry.ToolUse("b2", "Read", """{"file_path":"/b.kt"}""", parentToolUseId = "agent_b"),
            HistoryEntry.ToolResult("b2", "b-out2", false, parentToolUseId = "agent_b"),
            HistoryEntry.ToolResult("agent_a", "a done", false),
            HistoryEntry.ToolResult("agent_b", "b done", false),
        )
        val out = HistoryReplayRenderer.render(history, renderer)
        assertEquals(2, out.size)
        val cardA = out[0]
        val cardB = out[1]
        assertTrue(cardA.contains("alpha"))
        assertTrue(cardA.contains("""data-tool-id="a1""""))
        assertTrue(cardA.contains("1 step"))
        assertTrue(cardB.contains("beta"))
        assertTrue(cardB.contains("""data-tool-id="b1""""))
        assertTrue(cardB.contains("""data-tool-id="b2""""))
        assertTrue(cardB.contains("2 steps"))
    }

    @Test
    fun `orphan child whose parent agent is absent renders as a flat top-level block`() {
        val history = listOf(
            HistoryEntry.ToolUse("c1", "Read", """{"file_path":"/a.kt"}""", parentToolUseId = "missing_agent"),
            HistoryEntry.ToolResult("c1", "contents", false, parentToolUseId = "missing_agent"),
        )
        val out = HistoryReplayRenderer.render(history, renderer)
        // Orphan child must not crash and must NOT be folded into a sub-agent card.
        assertFalse("orphan must not produce a sub-agent card", out.joinToString("\n").contains("subagent-block"))
        // The Read tool renders as a file link; one output entry must be present.
        assertEquals("orphan renders as exactly one top-level block", 1, out.size)
    }

    @Test
    fun `top-level task tool calls reconstruct one todo widget instead of empty badges`() {
        val history = listOf(
            HistoryEntry.UserMessage("do stuff"),
            HistoryEntry.ToolUse("t1", "TaskCreate", """{"subject":"First task"}"""),
            HistoryEntry.ToolResult("t1", "Task #1 created successfully: First task", false),
            HistoryEntry.ToolUse("t2", "TaskCreate", """{"subject":"Second task"}"""),
            HistoryEntry.ToolResult("t2", "Task #2 created successfully: Second task", false),
            HistoryEntry.ToolUse("t3", "TaskUpdate", """{"taskId":"1","status":"completed"}"""),
            HistoryEntry.ToolResult("t3", "Updated task #1 status", false),
        )
        val out = HistoryReplayRenderer.render(history, renderer)
        val joined = out.joinToString("\n")
        // One user message + exactly one reconstructed widget (no per-call badges).
        assertEquals(2, out.size)
        assertTrue("widget reconstructed", joined.contains("task-widget"))
        assertTrue(joined.contains("First task"))
        assertTrue(joined.contains("Second task"))
        assertFalse("no empty per-call collapsed badges", joined.contains("tool-block-collapsed"))
    }
}
