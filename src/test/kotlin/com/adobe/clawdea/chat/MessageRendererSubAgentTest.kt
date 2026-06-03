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
}
