/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRendererGoalTest {

    private val renderer = MessageRenderer()

    @Test
    fun `goal banner shows condition, turn count, reason, and a clear control`() {
        val state = GoalController.GoalState(
            condition = "all tests pass",
            startMs = 0,
            turnCount = 3,
            latestReason = "one failure left",
        )
        val html = renderer.renderGoalBanner(state)
        assertTrue(html.contains("goal-banner"))
        assertTrue(html.contains("all tests pass"))
        assertTrue(html.contains("3 turns"))
        assertTrue(html.contains("one failure left"))
        assertTrue(html.contains("""data-action="run-slash-command""""))
        assertTrue(html.contains("""data-slash="/goal clear""""))
    }

    @Test
    fun `goal banner escapes HTML in the condition and reason`() {
        val state = GoalController.GoalState("<b>x</b>", 0, 1, "<i>y</i>")
        val html = renderer.renderGoalBanner(state)
        assertTrue(html.contains("&lt;b&gt;x&lt;/b&gt;"))
        assertTrue(html.contains("&lt;i&gt;y&lt;/i&gt;"))
        assertTrue(!html.contains("<b>x</b>"))
    }

    @Test
    fun `goal progress note carries the reason`() {
        val html = renderer.renderGoalProgress("two checks remaining")
        assertTrue(html.contains("two checks remaining"))
        assertTrue(html.contains("goal-progress"))
    }

    @Test
    fun `goal achieved note carries the condition`() {
        val state = GoalController.GoalState("ship it", 0, 4, "done", achieved = true)
        val html = renderer.renderGoalAchieved(state)
        assertTrue(html.contains("ship it"))
        assertTrue(html.contains("goal-achieved"))
    }

    @Test
    fun `goal banner shows starting before the first evaluation`() {
        val state = GoalController.GoalState(condition = "cond", startMs = 0, turnCount = 0)
        val html = renderer.renderGoalBanner(state)
        assertTrue(html.contains("starting"))
        assertFalse(html.contains("0 turn"))
    }
}
