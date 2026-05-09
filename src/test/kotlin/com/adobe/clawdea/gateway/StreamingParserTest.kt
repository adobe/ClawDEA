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
// src/test/kotlin/com/adobe/clawdea/gateway/StreamingParserTest.kt
package com.adobe.clawdea.gateway

import org.junit.Assert.*
import org.junit.Test

class StreamingParserTest {

    private val parser = StreamingParser()

    @Test
    fun `parse text delta event`() {
        val line = """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}"""
        val event = parser.parseLine(line)
        assertTrue(event is StreamEvent.TextDelta)
        assertEquals("Hello", (event as StreamEvent.TextDelta).text)
    }

    @Test
    fun `parse message stop event`() {
        val line = """data: {"type":"message_stop"}"""
        val event = parser.parseLine(line)
        assertTrue(event is StreamEvent.MessageStop)
    }

    @Test
    fun `parse message delta with stop reason`() {
        val line = """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""
        val event = parser.parseLine(line)
        assertTrue(event is StreamEvent.MessageStop)
        assertEquals("end_turn", (event as StreamEvent.MessageStop).stopReason)
    }

    @Test
    fun `parse content block start`() {
        val line = """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
        val event = parser.parseLine(line)
        assertTrue(event is StreamEvent.ContentBlockStart)
        assertEquals(0, (event as StreamEvent.ContentBlockStart).index)
    }

    @Test
    fun `parse ping event`() {
        val line = """event: ping"""
        val event = parser.parseLine(line)
        assertTrue(event is StreamEvent.Ping)
    }

    @Test
    fun `parse empty line returns null`() {
        val event = parser.parseLine("")
        assertNull(event)
    }

    @Test
    fun `parse error event`() {
        val line = """data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
        val event = parser.parseLine(line)
        assertTrue(event is StreamEvent.Error)
        assertEquals("Overloaded", (event as StreamEvent.Error).message)
    }

    @Test
    fun `parse text delta with escaped characters`() {
        val line = """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"line1\nline2"}}"""
        val event = parser.parseLine(line)
        assertTrue(event is StreamEvent.TextDelta)
        assertEquals("line1\nline2", (event as StreamEvent.TextDelta).text)
    }
}
