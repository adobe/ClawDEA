/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.cli

import org.junit.Assert.*
import org.junit.Test

class CliEventParserTest {

    private val parser = CliEventParser()

    @Test
    fun `parses system init event`() {
        val json = """{"type":"system","subtype":"init","session_id":"abc-123","model":"claude-opus-4-6","tools":["Bash","Edit","Read"]}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.SystemInit)
        val init = event as CliEvent.SystemInit
        assertEquals("abc-123", init.sessionId)
        assertEquals("claude-opus-4-6", init.model)
        assertEquals(listOf("Bash", "Edit", "Read"), init.tools)
    }

    @Test
    fun `parses text delta stream event`() {
        val json = """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.TextDelta)
        assertEquals("hello", (event as CliEvent.TextDelta).text)
    }

    @Test
    fun `parses assistant text message`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Hello world"}]}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.AssistantMessage)
        val msg = event as CliEvent.AssistantMessage
        assertEquals("Hello world", msg.text)
        assertTrue(msg.toolUses.isEmpty())
    }

    @Test
    fun `parses assistant tool use message`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_123","name":"Read","input":{"file_path":"/tmp/test.kt"}}]}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.AssistantMessage)
        val msg = event as CliEvent.AssistantMessage
        assertEquals(1, msg.toolUses.size)
        assertEquals("Read", msg.toolUses[0].name)
        assertEquals("toolu_123", msg.toolUses[0].id)
        assertTrue(msg.toolUses[0].input.contains("file_path"))
    }

    @Test
    fun `parses tool result event`() {
        val json = """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_123","type":"tool_result","content":"file contents here"}]}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.ToolResult)
        val result = event as CliEvent.ToolResult
        assertEquals("toolu_123", result.toolUseId)
        assertEquals("file contents here", result.content)
        assertFalse(result.isError)
    }

    @Test
    fun `parses tool result error event`() {
        val json = """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_789","type":"tool_result","content":"No match found for old_string","is_error":true}]}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.ToolResult)
        val result = event as CliEvent.ToolResult
        assertEquals("toolu_789", result.toolUseId)
        assertEquals("No match found for old_string", result.content)
        assertTrue(result.isError)
    }

    @Test
    fun `parses result success event`() {
        val json = """{"type":"result","subtype":"success","is_error":false,"result":"Done!","total_cost_usd":0.05,"session_id":"abc-123"}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.Result)
        val result = event as CliEvent.Result
        assertEquals("Done!", result.text)
        assertFalse(result.isError)
        assertEquals(0.05, result.costUsd, 0.001)
        assertEquals("abc-123", result.sessionId)
    }

    @Test
    fun `extracts contextTokens and contextWindow from result usage and modelUsage`() {
        val json = """{"type":"result","subtype":"success","is_error":false,"result":"hi","total_cost_usd":0.01,"session_id":"s1","usage":{"input_tokens":10,"cache_creation_input_tokens":1000,"cache_read_input_tokens":500,"output_tokens":42},"modelUsage":{"claude-opus-4-7":{"inputTokens":10,"contextWindow":1000000,"maxOutputTokens":32000}}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.Result)
        val result = event as CliEvent.Result
        assertEquals(1510, result.contextTokens)
        assertEquals(1_000_000, result.contextWindow)
    }

    @Test
    fun `parses result error event`() {
        val json = """{"type":"result","subtype":"error","is_error":true,"result":"Something failed","total_cost_usd":0.0,"session_id":"abc-123"}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.Result)
        val result = event as CliEvent.Result
        assertTrue(result.isError)
        assertEquals("Something failed", result.text)
    }

    @Test
    fun `returns unknown with rawType for unrecognized event type`() {
        val json = """{"type":"something_new","data":"whatever"}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.Unknown)
        assertEquals("something_new", (event as CliEvent.Unknown).rawType)
    }

    @Test
    fun `returns unknown with empty rawType for malformed JSON`() {
        val event = parser.parse("not json at all")
        assertTrue(event is CliEvent.Unknown)
        assertEquals("", (event as CliEvent.Unknown).rawType)
    }

    @Test
    fun `parses assistant message with mixed text and tool use`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Let me read that file."},{"type":"tool_use","id":"toolu_456","name":"Bash","input":{"command":"ls -la"}}]}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.AssistantMessage)
        val msg = event as CliEvent.AssistantMessage
        assertEquals("Let me read that file.", msg.text)
        assertEquals(1, msg.toolUses.size)
        assertEquals("Bash", msg.toolUses[0].name)
    }

    @Test
    fun `parses content block start stream event as unknown with stream_event rawType`() {
        val json = """{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.Unknown)
        assertEquals("stream_event", (event as CliEvent.Unknown).rawType)
    }

    @Test
    fun `parses empty content array as assistant message`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[]}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.AssistantMessage)
        val msg = event as CliEvent.AssistantMessage
        assertEquals("", msg.text)
        assertTrue(msg.toolUses.isEmpty())
    }

    @Test
    fun `parses Edit tool with braces in old_string and new_string`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_edit1","name":"Edit","input":{"file_path":"/tmp/Service.kt","old_string":"fun start() {\n    println(\"hello\")\n}","new_string":"fun start() {\n    log.info(\"starting\")\n    println(\"hello\")\n}"}}]}}"""
        val event = parser.parse(json)
        assertTrue("Expected AssistantMessage but got ${event::class.simpleName}", event is CliEvent.AssistantMessage)
        val msg = event as CliEvent.AssistantMessage
        assertEquals(1, msg.toolUses.size)
        assertEquals("Edit", msg.toolUses[0].name)
        assertEquals("toolu_edit1", msg.toolUses[0].id)
        assertTrue(msg.toolUses[0].input.contains("file_path"))
        assertTrue(msg.toolUses[0].input.contains("old_string"))
        assertTrue(msg.toolUses[0].input.contains("new_string"))
    }

    @Test
    fun `parses Edit tool with unbalanced braces in code`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Adding a log."},{"type":"tool_use","id":"toolu_edit2","name":"Edit","input":{"file_path":"/tmp/App.kt","old_string":"class App {","new_string":"class App {\n    private val log = Logger.getLogger(\"App\")"}}]}}"""
        val event = parser.parse(json)
        assertTrue("Expected AssistantMessage but got ${event::class.simpleName}", event is CliEvent.AssistantMessage)
        val msg = event as CliEvent.AssistantMessage
        assertEquals("Adding a log.", msg.text)
        assertEquals(1, msg.toolUses.size)
        assertEquals("Edit", msg.toolUses[0].name)
    }

    @Test
    fun `parses multiple tool uses including Edit with braces`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_r1","name":"Read","input":{"file_path":"/tmp/A.kt"}},{"type":"tool_use","id":"toolu_e1","name":"Edit","input":{"file_path":"/tmp/A.kt","old_string":"fun go() {","new_string":"fun go() {\n    log.info(\"go\")"}}]}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.AssistantMessage)
        val msg = event as CliEvent.AssistantMessage
        assertEquals(2, msg.toolUses.size)
        assertEquals("Read", msg.toolUses[0].name)
        assertEquals("Edit", msg.toolUses[1].name)
    }

    @Test
    fun `parses Edit tool with nested JSON-like content in strings`() {
        val json = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_e3","name":"Edit","input":{"file_path":"/tmp/config.json","old_string":"{\"key\": \"value\"}","new_string":"{\"key\": \"new_value\", \"extra\": {\"nested\": true}}"}}]}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.AssistantMessage)
        val msg = event as CliEvent.AssistantMessage
        assertEquals(1, msg.toolUses.size)
        assertEquals("Edit", msg.toolUses[0].name)
    }

}
