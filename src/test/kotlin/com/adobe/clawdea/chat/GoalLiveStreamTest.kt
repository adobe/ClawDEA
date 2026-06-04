/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.chat

import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.cli.CliEventParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays a REAL captured `/goal` run (set → feedback ×2 → result) through the
 * parser and a GoalController simulation mirroring EventStreamHandler, proving
 * the feedback messages parse and the trailing result drives the goal to
 * "achieved".
 *
 * Fixture: src/test/resources/cli-fixtures/goal-stream.ndjson
 */
class GoalLiveStreamTest {

    private val parser = CliEventParser()

    private fun fixtureLines(): List<String> =
        javaClass.getResourceAsStream("/cli-fixtures/goal-stream.ndjson")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }

    @Test
    fun `captured goal run yields feedback events and an achieved finalization`() {
        val controller = GoalController()
        var feedbackCount = 0
        var achieved: GoalController.GoalState? = null

        for (line in fixtureLines()) {
            when (val event = parser.parse(line)) {
                is CliEvent.GoalFeedback -> {
                    feedbackCount++
                    controller.onFeedback(event.condition, event.reason)
                }
                is CliEvent.Result -> {
                    achieved = controller.onResult()
                }
                else -> {}
            }
        }

        assertEquals("fixture contains two Stop-hook feedback messages", 2, feedbackCount)
        assertTrue("the trailing result finalized the goal", achieved?.achieved == true)
        assertEquals(2, achieved!!.turnCount)
    }
}
