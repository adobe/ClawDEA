/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.chat

import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.cli.CliEventParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test over a REAL captured `claude --output-format stream-json
 * --include-partial-messages` run that dispatches an `Agent` sub-agent which
 * runs one inner Bash call. Proves the live path: the parser threads
 * `parent_tool_use_id`, and the same routing the EventStreamHandler uses
 * (parentCardFor + recordStep) attributes the inner tool call to the sub-agent
 * card, yielding a non-zero step count.
 *
 * Fixture: src/test/resources/cli-fixtures/subagent-live.ndjson
 */
class SubAgentLiveStreamTest {

    private val parser = CliEventParser()

    private fun fixtureLines(): List<String> =
        javaClass.getResourceAsStream("/cli-fixtures/subagent-live.ndjson")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }

    @Test
    fun `inner tool use carries the dispatching Agent id as parent`() {
        val events = fixtureLines().map { parser.parse(it) }

        val agentDispatch = events.filterIsInstance<CliEvent.AssistantMessage>()
            .firstOrNull { msg -> msg.toolUses.any { it.name == "Agent" } }
        assertNotNull("expected an Agent dispatch in the fixture", agentDispatch)
        val agentId = agentDispatch!!.toolUses.first { it.name == "Agent" }.id
        assertEquals("dispatch itself is main-agent (null parent)", null, agentDispatch.parentToolUseId)

        val innerToolMsg = events.filterIsInstance<CliEvent.AssistantMessage>()
            .firstOrNull { it.parentToolUseId != null && it.toolUses.isNotEmpty() }
        assertNotNull("expected an inner sub-agent tool_use with a parent", innerToolMsg)
        assertEquals("inner tool_use parent must equal the Agent id", agentId, innerToolMsg!!.parentToolUseId)
    }

    @Test
    fun `routing the captured stream counts one inner step and finalizes DONE`() {
        val controller = SubAgentController()
        var finalStepCount = -1
        var finalStatus: SubAgentController.Status? = null

        for (line in fixtureLines()) {
            when (val event = parser.parse(line)) {
                is CliEvent.AssistantMessage -> {
                    val parentCard = controller.parentCardFor(event.parentToolUseId)
                    if (parentCard != null) {
                        // Inner sub-agent content: count each inner tool call.
                        event.toolUses.forEach { controller.recordStep(parentCard) }
                    } else {
                        // Top level: open a card for each Agent dispatch.
                        event.toolUses.filter { SubAgentController.isSubAgentTool(it.name) }
                            .forEach { controller.register(it.id, "agent", "", 0) }
                    }
                }
                is CliEvent.ToolResult -> {
                    if (controller.isActive(event.toolUseId)) {
                        val status = if (event.isError) SubAgentController.Status.ERROR
                        else SubAgentController.Status.DONE
                        val state = controller.finalize(event.toolUseId, status)
                        finalStepCount = state!!.stepCount
                        finalStatus = status
                    }
                }
                else -> {}
            }
        }

        assertEquals("sub-agent should have finalized DONE", SubAgentController.Status.DONE, finalStatus)
        assertTrue("expected at least one counted inner step, got $finalStepCount", finalStepCount >= 1)
    }
}
