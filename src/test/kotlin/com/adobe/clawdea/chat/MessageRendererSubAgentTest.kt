/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.chat

import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRendererSubAgentTest {

    private val renderer = MessageRenderer()

    @Test
    fun `sub-agent card carries the card id, agent type, and children container`() {
        val html = renderer.renderSubAgentCard("wiki-librarian", "Research chat UI", "toolu_p")
        assertTrue(html.contains("subagent-block"))
        assertTrue(html.contains("expanded"))
        assertTrue(html.contains("""data-tool-id="toolu_p""""))
        assertTrue(html.contains("wiki-librarian"))
        assertTrue(html.contains("Research chat UI"))
        assertTrue(html.contains("""class="subagent-children""""))
        assertTrue(html.contains("data-action=\"toggle-subagent\""))
    }

    @Test
    fun `inner tool use renders a compact expandable one-liner with its own id`() {
        val html = renderer.renderInnerToolUse("Read", """{"file_path":"/a.kt"}""", "toolu_child")
        assertTrue(html.contains("""class="subagent-step""""))
        assertTrue(html.contains("""data-tool-id="toolu_child""""))
        assertTrue(html.contains("data-action=\"toggle-subagent-step\""))
        assertTrue(html.contains("Read"))
    }

    @Test
    fun `inner tool use escapes HTML in arguments`() {
        val html = renderer.renderInnerToolUse("Bash", """{"command":"<script>"}""", "id1")
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(!html.contains("<script>"))
    }

    @Test
    fun `summary reflects done status, step count, and result text`() {
        val html = renderer.renderSubAgentSummary(SubAgentController.Status.DONE, stepCount = 17, resultText = "all good")
        assertTrue(html.contains("17"))
        assertTrue(html.contains("all good"))
        assertTrue(html.contains("subagent-summary"))
    }

    @Test
    fun `error summary is marked with an error class`() {
        val html = renderer.renderSubAgentSummary(SubAgentController.Status.ERROR, stepCount = 3, resultText = "boom")
        assertTrue(html.contains("subagent-summary-error"))
    }

    @Test
    fun `inner tool use inlines result when result content supplied`() {
        val html = renderer.renderInnerToolUse("Read", """{"file_path":"/a.kt"}""", "toolu_child", "the file contents")
        assertTrue(html.contains("tool-body-collapsible"))
        assertTrue(html.contains("the file contents"))
    }

    @Test
    fun `inner tool use omits result block when no result content`() {
        val html = renderer.renderInnerToolUse("Read", """{"file_path":"/a.kt"}""", "toolu_child")
        assertTrue(!html.contains("tool-body-collapsible"))
    }

    @Test
    fun `history card is collapsed and carries summary, type, steps, and children`() {
        val children = """<div class="subagent-step" data-tool-id="c1"></div>"""
        val html = renderer.renderSubAgentCardFromHistory(
            agentType = "wiki-librarian",
            description = "Research chat UI",
            toolUseId = "agent_1",
            status = SubAgentController.Status.DONE,
            stepCount = 2,
            resultText = "final summary",
            childrenHtml = children,
        )
        assertTrue(html.contains("subagent-block"))
        assertTrue("history card must NOT be expanded", !html.contains("expanded"))
        assertTrue(html.contains("wiki-librarian"))
        assertTrue(html.contains("subagent-summary"))
        assertTrue(html.contains("2 steps"))
        assertTrue(html.contains(children))
    }
}
