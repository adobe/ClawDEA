/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CliEventParserSubAgentTest {

    private val parser = CliEventParser()

    @Test
    fun `main-agent assistant message has null parentToolUseId`() {
        val json = """{"type":"assistant","parent_tool_use_id":null,"message":{"content":[{"type":"text","text":"hi"}]}}"""
        val event = parser.parse(json)
        assertEquals(CliEvent.AssistantMessage("hi", emptyList(), null), event)
    }

    @Test
    fun `sub-agent assistant message carries its parent id`() {
        val json = """{"type":"assistant","parent_tool_use_id":"toolu_parent","message":{"content":[{"type":"tool_use","id":"toolu_child","name":"Read","input":{"file_path":"/a.kt"}}]}}"""
        val event = parser.parse(json) as CliEvent.AssistantMessage
        assertEquals("toolu_parent", event.parentToolUseId)
        assertEquals("toolu_child", event.toolUses.single().id)
    }

    @Test
    fun `sub-agent tool result carries its parent id`() {
        val json = """{"type":"user","parent_tool_use_id":"toolu_parent","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_child","content":"done"}]}}"""
        val event = parser.parse(json) as CliEvent.ToolResult
        assertEquals("toolu_child", event.toolUseId)
        assertEquals("toolu_parent", event.parentToolUseId)
    }

    @Test
    fun `text delta carries parent id when present`() {
        val json = """{"type":"stream_event","parent_tool_use_id":"toolu_parent","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"x"}}}"""
        val event = parser.parse(json) as CliEvent.TextDelta
        assertEquals("x", event.text)
        assertEquals("toolu_parent", event.parentToolUseId)
    }

    @Test
    fun `text delta has null parent for main agent`() {
        val json = """{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"x"}}}"""
        val event = parser.parse(json) as CliEvent.TextDelta
        assertNull(event.parentToolUseId)
    }

    @Test
    fun `real wire order - parent_tool_use_id after message is still captured`() {
        // In the real CLI wire format (confirmed via latest.ndjson line 10) the envelope key order is:
        // type → message → parent_tool_use_id → session_id → uuid
        // This test proves the parser is field-order independent against that real shape.
        val json = """{"type":"assistant","message":{"model":"claude-sonnet-4-6","id":"msg_abc","type":"message","role":"assistant","content":[{"type":"text","text":"hello"}],"stop_reason":null},"parent_tool_use_id":"toolu_real_parent","session_id":"sess_1","uuid":"uuid_1"}"""
        val event = parser.parse(json) as CliEvent.AssistantMessage
        assertEquals("toolu_real_parent", event.parentToolUseId)
        assertEquals("hello", event.text)
    }

    @Test
    fun `absent parent_tool_use_id key yields null parentToolUseId`() {
        // When the key is entirely omitted (not present as null), parentToolUseId must still be null.
        val json = """{"type":"assistant","message":{"content":[{"type":"text","text":"standalone"}]}}"""
        val event = parser.parse(json) as CliEvent.AssistantMessage
        assertNull(event.parentToolUseId)
        assertEquals("standalone", event.text)
    }
}
