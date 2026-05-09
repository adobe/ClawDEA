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
package com.adobe.clawdea.mcp

import org.junit.Assert.*
import org.junit.Test

class McpProtocolTest {

    @Test
    fun `parseToolArguments unescapes newlines in string values`() {
        val json = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"propose_edit","arguments":{"file_path":"/tmp/test.kt","old_string":"line1\nline2","new_string":"line1\nreplaced"}}}"""
        val args = McpProtocol.parseToolArguments(json)
        assertEquals("line1\nline2", args["old_string"])
        assertEquals("line1\nreplaced", args["new_string"])
    }

    @Test
    fun `parseToolArguments unescapes tabs and backslashes`() {
        val json = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"test","arguments":{"value":"col1\tcol2\\end"}}}"""
        val args = McpProtocol.parseToolArguments(json)
        assertEquals("col1\tcol2\\end", args["value"])
    }

    @Test
    fun `parseToolArguments handles plain strings without escapes`() {
        val json = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"test","arguments":{"key":"simple value"}}}"""
        val args = McpProtocol.parseToolArguments(json)
        assertEquals("simple value", args["key"])
    }

    @Test
    fun `parseToolArguments handles numeric values`() {
        val json = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"test","arguments":{"line":42}}}"""
        val args = McpProtocol.parseToolArguments(json)
        assertEquals("42", args["line"])
    }

    @Test
    fun `unescapeJsonString converts all escape sequences`() {
        assertEquals("a\nb", McpProtocol.unescapeJsonString("a\\nb"))
        assertEquals("a\rb", McpProtocol.unescapeJsonString("a\\rb"))
        assertEquals("a\tb", McpProtocol.unescapeJsonString("a\\tb"))
        assertEquals("a\"b", McpProtocol.unescapeJsonString("a\\\"b"))
        assertEquals("a\\b", McpProtocol.unescapeJsonString("a\\\\b"))
        assertEquals("a/b", McpProtocol.unescapeJsonString("a\\/b"))
    }

    @Test
    fun `unescapeJsonString returns plain string unchanged`() {
        assertEquals("hello world", McpProtocol.unescapeJsonString("hello world"))
    }

    @Test
    fun `escapeJsonString round-trips with unescapeJsonString`() {
        val original = "line1\nline2\ttab\r\n\"quoted\"\nbackslash\\"
        val escaped = McpProtocol.escapeJsonString(original)
        val unescaped = McpProtocol.unescapeJsonString(escaped)
        assertEquals(original, unescaped)
    }
}
