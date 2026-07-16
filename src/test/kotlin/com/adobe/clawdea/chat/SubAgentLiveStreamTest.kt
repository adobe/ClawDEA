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

    // --- Background sub-agent containment (event-driven: task_started / _notification) ---

    private data class Outcome(
        val steps: Int,
        val status: SubAgentController.Status?,
        val leakedSteps: Int,
    )

    /**
     * Faithfully mirrors the fixed [EventStreamHandler] routing for the subset of
     * events that drive sub-agent card lifecycle, so the containment logic runs
     * headlessly. Tracks a single card by [cardId]:
     *  - inner AssistantMessage steps whose parent resolves → counted (contained);
     *  - inner steps whose parent does NOT resolve → leaked (would hit main chat);
     *  - task_started → mark background (keep open);
     *  - launch-ack ToolResult on a background card → keep open (no finalize);
     *  - task_notification → finalize (completed→DONE, else ABORTED);
     *  - a turn Result sweep SKIPS background cards.
     */
    private fun route(events: List<CliEvent>, cardId: String): Outcome {
        val c = SubAgentController()
        var steps = 0
        var leaked = 0
        var finalStatus: SubAgentController.Status? = null
        fun finalize(id: String, status: SubAgentController.Status) {
            val s = c.finalize(id, status) ?: return
            if (id == cardId) finalStatus = status
        }
        for (event in events) {
            when (event) {
                is CliEvent.AssistantMessage -> {
                    val parent = c.parentCardFor(event.parentToolUseId)
                    if (parent != null) {
                        event.toolUses.forEach { c.recordStep(parent); if (parent == cardId) steps++ }
                    } else if (event.parentToolUseId != null) {
                        // Inner event whose parent card is gone → leaks to main chat.
                        if (event.parentToolUseId == cardId) leaked += event.toolUses.size
                    } else {
                        event.toolUses.filter { SubAgentController.isSubAgentTool(it.name) }
                            .forEach { c.register(it.id, "agent", "", 0) }
                    }
                }
                is CliEvent.ToolResult -> {
                    // Launch ack for a background card: keep open (do not finalize).
                    if (c.isActive(event.toolUseId) && c.isLaunchedInBackground(event.toolUseId)) {
                        // no-op (ack)
                    } else if (c.isActive(event.toolUseId)) {
                        val st = if (event.isError) SubAgentController.Status.ERROR
                        else SubAgentController.Status.DONE
                        finalize(event.toolUseId, st)
                    }
                }
                is CliEvent.BackgroundTask -> when (event.phase) {
                    CliEvent.BackgroundTask.Phase.STARTED ->
                        if (c.isActive(event.toolUseId)) c.markLaunchedInBackground(event.toolUseId)
                    CliEvent.BackgroundTask.Phase.PROGRESS -> {}
                    CliEvent.BackgroundTask.Phase.NOTIFICATION ->
                        if (c.isActive(event.toolUseId)) {
                            finalize(
                                event.toolUseId,
                                if (event.isCompleted) SubAgentController.Status.DONE
                                else SubAgentController.Status.ABORTED,
                            )
                        }
                }
                is CliEvent.Result -> {
                    // Turn-end sweep: finalize FOREGROUND survivors ABORTED, SKIP background.
                    for (id in c.activeIds()) {
                        if (c.isLaunchedInBackground(id)) continue
                        finalize(id, SubAgentController.Status.ABORTED)
                    }
                }
                else -> {}
            }
        }
        return Outcome(steps, finalStatus, leaked)
    }

    private fun result() = CliEvent.Result(text = "", isError = false, costUsd = 0.0, sessionId = "")
    private fun started(id: String) = CliEvent.BackgroundTask(id, CliEvent.BackgroundTask.Phase.STARTED)
    private fun ack(id: String) = CliEvent.ToolResult(id, "launched", isError = false, parentToolUseId = null)
    private fun innerStep(parent: String, tool: String = "Bash") =
        CliEvent.AssistantMessage("", listOf(CliEvent.ToolUse("i_$parent", tool, "{}")), parent, "")
    private fun dispatch(id: String) =
        CliEvent.AssistantMessage("", listOf(CliEvent.ToolUse(id, "Agent", "{}")), null, "")
    private fun notification(id: String, status: String) =
        CliEvent.BackgroundTask(id, CliEvent.BackgroundTask.Phase.NOTIFICATION, status = status, summary = "ok")

    @Test
    fun `foreground agent still finalizes on its own result (no regression)`() {
        // Real captured fixture is a foreground agent: no task_started, so its
        // same-id ToolResult finalizes the card exactly as before.
        val events = fixtureLines().map { parser.parse(it) }
        val agentId = events.filterIsInstance<CliEvent.AssistantMessage>()
            .first { m -> m.toolUses.any { it.name == "Agent" } }
            .toolUses.first { it.name == "Agent" }.id
        val out = route(events, agentId)
        assertEquals(SubAgentController.Status.DONE, out.status)
        assertTrue("inner step counted before finalize", out.steps >= 1)
        assertEquals("nothing leaks for a foreground agent", 0, out.leakedSteps)
    }

    @Test
    fun `background agent contains inner steps across the turn Result and closes on task_notification`() {
        val id = "toolu_bg"
        // Real ordering from the live capture: dispatch → task_started → ack →
        // inner steps → turn Result (agent still running) → more steps → completion.
        val events = listOf(
            dispatch(id),
            started(id),
            ack(id),
            innerStep(id), innerStep(id),
            result(),               // turn ends while the bg agent is still working
            innerStep(id), innerStep(id), innerStep(id),
            notification(id, "completed"),
        )
        val out = route(events, id)
        assertEquals("all 5 inner steps contained, none leaked", 5, out.steps)
        assertEquals("no leak across the turn boundary", 0, out.leakedSteps)
        assertEquals("closes DONE on task_notification", SubAgentController.Status.DONE, out.status)
    }

    @Test
    fun `stopped background agent closes ABORTED`() {
        val id = "toolu_bg"
        val events = listOf(dispatch(id), started(id), ack(id), innerStep(id), result(), notification(id, "stopped"))
        val out = route(events, id)
        assertEquals(SubAgentController.Status.ABORTED, out.status)
        assertEquals(0, out.leakedSteps)
    }

    @Test
    fun `three parallel background agents each stay contained past the Result (the reported bug)`() {
        val a = "toolu_a"; val b = "toolu_b"; val d = "toolu_d"
        val events = listOf(
            dispatch(a), started(a), ack(a),
            dispatch(b), started(b), ack(b),
            dispatch(d), started(d), ack(d),
            innerStep(a), innerStep(b), innerStep(d),
            result(),                       // main turn ends; all three still running
            innerStep(a), innerStep(b), innerStep(d),   // these leaked before the fix
            notification(a, "completed"), notification(b, "completed"), notification(d, "completed"),
        )
        // Each card sees 2 inner steps (one before, one after Result), 0 leaked.
        for (id in listOf(a, b, d)) {
            val out = route(events, id)
            assertEquals("card $id contains both steps", 2, out.steps)
            assertEquals("card $id leaks nothing across Result", 0, out.leakedSteps)
            assertEquals("card $id closes DONE", SubAgentController.Status.DONE, out.status)
        }
    }

    @Test
    fun `WITHOUT the fix the post-Result step leaks (characterization of the bug)`() {
        // Old behavior: finalize on the ack ToolResult, and the Result sweep would
        // also wipe the card — so a step after that finds no active parent.
        val id = "toolu_bg"
        val c = SubAgentController()
        var leaked = 0; var contained = 0
        val events = listOf(dispatch(id), ack(id), innerStep(id))  // no task_started, no keep-open
        for (event in events) when (event) {
            is CliEvent.AssistantMessage -> {
                val parent = c.parentCardFor(event.parentToolUseId)
                if (parent != null) event.toolUses.forEach { c.recordStep(parent); contained++ }
                else if (event.parentToolUseId != null) leaked += event.toolUses.size
                else event.toolUses.filter { SubAgentController.isSubAgentTool(it.name) }
                    .forEach { c.register(it.id, "agent", "", 0) }
            }
            is CliEvent.ToolResult ->
                if (c.isActive(event.toolUseId)) c.finalize(event.toolUseId, SubAgentController.Status.DONE)
            else -> {}
        }
        assertEquals("old behavior leaks the inner step", 1, leaked)
        assertEquals("old behavior contains nothing", 0, contained)
    }
}
