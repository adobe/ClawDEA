/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser coverage for the `type:"system"` background sub-agent lifecycle events,
 * over payloads captured verbatim from `claude` 2.1.210
 * (`--output-format stream-json --verbose`). These drive [CliEvent.BackgroundTask],
 * keyed by `tool_use_id` (the dispatching Agent's id = the sub-agent card id).
 */
class CliEventParserBackgroundTaskTest {

    private val parser = CliEventParser()

    @Test
    fun `task_started parses to a STARTED background task keyed by tool_use_id`() {
        val json = """{"type":"system","subtype":"task_started","task_id":"ad665dcb22cb90e04","tool_use_id":"toolu_bdrk_0114g88b","description":"Run ls twice","subagent_type":"general-purpose","task_type":"local_agent","session_id":"s1"}"""
        val e = parser.parse(json) as CliEvent.BackgroundTask
        assertEquals(CliEvent.BackgroundTask.Phase.STARTED, e.phase)
        assertEquals("toolu_bdrk_0114g88b", e.toolUseId)
    }

    @Test
    fun `task_progress carries the last tool name and inner tool count`() {
        val json = """{"type":"system","subtype":"task_progress","task_id":"ad665dcb22cb90e04","tool_use_id":"toolu_bdrk_0114g88b","description":"Running","subagent_type":"general-purpose","usage":{"total_tokens":38213,"tool_uses":2,"duration_ms":9279},"last_tool_name":"Bash","session_id":"s1"}"""
        val e = parser.parse(json) as CliEvent.BackgroundTask
        assertEquals(CliEvent.BackgroundTask.Phase.PROGRESS, e.phase)
        assertEquals("toolu_bdrk_0114g88b", e.toolUseId)
        assertEquals("Bash", e.lastToolName)
        assertEquals(2, e.toolUses)
    }

    @Test
    fun `task_notification completed is a NOTIFICATION with completed status and summary`() {
        val json = """{"type":"system","subtype":"task_notification","task_id":"ad665dcb22cb90e04","tool_use_id":"toolu_bdrk_0114g88b","status":"completed","output_file":"/tmp/x.output","summary":"ok","usage":{"total_tokens":42894,"tool_uses":2,"duration_ms":13156},"session_id":"s1"}"""
        val e = parser.parse(json) as CliEvent.BackgroundTask
        assertEquals(CliEvent.BackgroundTask.Phase.NOTIFICATION, e.phase)
        assertEquals("toolu_bdrk_0114g88b", e.toolUseId)
        assertEquals("completed", e.status)
        assertEquals("ok", e.summary)
        assertTrue(e.isCompleted)
    }

    @Test
    fun `task_notification stopped is a NOTIFICATION that is not completed`() {
        val json = """{"type":"system","subtype":"task_notification","task_id":"t1","tool_use_id":"toolu_bg","status":"stopped","summary":"Run sleep loop","session_id":"s1"}"""
        val e = parser.parse(json) as CliEvent.BackgroundTask
        assertEquals(CliEvent.BackgroundTask.Phase.NOTIFICATION, e.phase)
        assertEquals("stopped", e.status)
        assertFalse(e.isCompleted)
    }

    @Test
    fun `background_tasks_changed is ignored (roster snapshot, no per-card action)`() {
        val json = """{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"t1","task_type":"local_agent","description":"x"}],"session_id":"s1"}"""
        val e = parser.parse(json)
        assertTrue(e is CliEvent.Unknown)
    }

    @Test
    fun `task_updated without a tool_use_id is ignored (task_id-only roster nudge)`() {
        val json = """{"type":"system","subtype":"task_updated","task_id":"t1","patch":{"status":"completed","end_time":1784238407585},"session_id":"s1"}"""
        val e = parser.parse(json)
        assertTrue("task_updated carries no tool_use_id to route by", e is CliEvent.Unknown)
    }

    @Test
    fun `a real system init is still parsed as SystemInit (not misrouted)`() {
        val json = """{"type":"system","subtype":"init","session_id":"s1","model":"claude-opus-4-8","tools":["Bash","Read"]}"""
        val e = parser.parse(json) as CliEvent.SystemInit
        assertEquals("s1", e.sessionId)
        assertEquals("claude-opus-4-8", e.model)
    }
}
