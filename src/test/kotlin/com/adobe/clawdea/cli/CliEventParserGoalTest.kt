/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliEventParserGoalTest {

    private val parser = CliEventParser()

    @Test
    fun `stop hook feedback parses into GoalFeedback with condition and reason`() {
        val json = """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Stop hook feedback:\n[all tests pass]: Two tests still fail in auth."}]}}"""
        val event = parser.parse(json) as CliEvent.GoalFeedback
        assertEquals("all tests pass", event.condition)
        assertEquals("Two tests still fail in auth.", event.reason)
    }

    @Test
    fun `stop hook feedback without bracket shape keeps full remainder as reason`() {
        val json = """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Stop hook feedback: keep going"}]}}"""
        val event = parser.parse(json) as CliEvent.GoalFeedback
        assertEquals("", event.condition)
        assertEquals("keep going", event.reason)
    }

    @Test
    fun `ordinary tool_result user message is not treated as goal feedback`() {
        val json = """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"done"}]}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.ToolResult)
    }

    @Test
    fun `ordinary user text is not goal feedback`() {
        val json = """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"hello there"}]}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.Unknown)
    }
}
