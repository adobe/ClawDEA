/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentControllerTest {

    @Test
    fun `Agent tool name is recognized as a sub-agent dispatch`() {
        assertTrue(SubAgentController.isSubAgentTool("Agent"))
        assertFalse(SubAgentController.isSubAgentTool("Task"))
        assertFalse(SubAgentController.isSubAgentTool("TaskCreate"))
        assertFalse(SubAgentController.isSubAgentTool("Read"))
    }

    @Test
    fun `register makes an id active and parentCardFor resolves it`() {
        val c = SubAgentController()
        c.register("toolu_p", "wiki-librarian", "Research X", nowMs = 1000)
        assertTrue(c.isActive("toolu_p"))
        assertEquals("toolu_p", c.parentCardFor("toolu_p"))
    }

    @Test
    fun `parentCardFor returns null for unknown or null parent`() {
        val c = SubAgentController()
        c.register("toolu_p", "x", "y", nowMs = 0)
        assertNull(c.parentCardFor("toolu_other"))
        assertNull(c.parentCardFor(null))
    }

    @Test
    fun `recordStep increments and returns the running count`() {
        val c = SubAgentController()
        c.register("toolu_p", "x", "y", nowMs = 0)
        assertEquals(1, c.recordStep("toolu_p"))
        assertEquals(2, c.recordStep("toolu_p"))
        assertEquals(-1, c.recordStep("unknown"))
    }

    @Test
    fun `finalize removes the id, sets status, and returns the state`() {
        val c = SubAgentController()
        c.register("toolu_p", "agent", "desc", nowMs = 100)
        c.recordStep("toolu_p")
        val state = c.finalize("toolu_p", SubAgentController.Status.DONE)!!
        assertEquals("agent", state.agentType)
        assertEquals(1, state.stepCount)
        assertEquals(SubAgentController.Status.DONE, state.status)
        assertFalse(c.isActive("toolu_p"))
        assertNull(c.finalize("toolu_p", SubAgentController.Status.DONE))
    }

    @Test
    fun `two parallel sub-agents are tracked independently`() {
        val c = SubAgentController()
        c.register("a", "agentA", "dA", nowMs = 0)
        c.register("b", "agentB", "dB", nowMs = 0)
        c.recordStep("a"); c.recordStep("a"); c.recordStep("b")
        assertEquals(listOf("a", "b"), c.activeIds())
        assertEquals(2, c.finalize("a", SubAgentController.Status.DONE)!!.stepCount)
        assertEquals(1, c.finalize("b", SubAgentController.Status.DONE)!!.stepCount)
        assertTrue(c.activeIds().isEmpty())
    }

    @Test
    fun `finalize with ABORTED stamps aborted status and removes the id`() {
        val c = SubAgentController()
        c.register("toolu_p", "agent", "desc", nowMs = 0)
        c.recordStep("toolu_p")
        c.recordStep("toolu_p")
        val state = c.finalize("toolu_p", SubAgentController.Status.ABORTED)!!
        assertEquals(SubAgentController.Status.ABORTED, state.status)
        assertEquals(2, state.stepCount)
        assertFalse(c.isActive("toolu_p"))
    }
}
