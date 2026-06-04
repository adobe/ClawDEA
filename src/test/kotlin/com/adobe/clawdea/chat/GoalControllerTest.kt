/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalControllerTest {

    @Test
    fun `onSet activates a goal with zero turns`() {
        val c = GoalController()
        c.onSet("all tests pass", nowMs = 1000)
        val s = c.current()!!
        assertEquals("all tests pass", s.condition)
        assertEquals(0, s.turnCount)
        assertEquals(1000, s.startMs)
    }

    @Test
    fun `onFeedback increments turns and records the latest reason`() {
        val c = GoalController()
        c.onSet("cond", nowMs = 0)
        c.onFeedback("cond", "still two failures", nowMs = 5)
        c.onFeedback("cond", "one failure left", nowMs = 9)
        val s = c.current()!!
        assertEquals(2, s.turnCount)
        assertEquals("one failure left", s.latestReason)
    }

    @Test
    fun `onFeedback self-activates when no goal was set (resume case)`() {
        val c = GoalController()
        c.onFeedback("restored condition", "working", nowMs = 3)
        val s = c.current()!!
        assertEquals("restored condition", s.condition)
        assertEquals(1, s.turnCount)
    }

    @Test
    fun `onResult marks achieved once then deactivates`() {
        val c = GoalController()
        c.onSet("cond", nowMs = 0)
        c.onFeedback("cond", "r", nowMs = 1)
        val achieved = c.onResult()!!
        assertTrue(achieved.achieved)
        assertEquals("cond", achieved.condition)
        assertNull("goal cleared after achieve", c.current())
        assertNull("second result is a no-op", c.onResult())
    }

    @Test
    fun `onClear deactivates`() {
        val c = GoalController()
        c.onSet("cond", nowMs = 0)
        c.onClear()
        assertNull(c.current())
        assertNull("result after clear is a no-op", c.onResult())
    }
}
