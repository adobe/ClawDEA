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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [McpProtocol.parseToolArguments] / `parseSimpleObject`
 * with nested object and array values. The naive regex-based parser previously
 * dropped nested objects entirely, which broke the `request_permission` tool
 * whose `input` argument is a nested object.
 */
class McpProtocolNestedObjectTest {

    @Test
    fun `scalar arguments still parse correctly`() {
        val body = """{"method":"tools/call","params":{"name":"Read","arguments":{"file_path":"/tmp/x","line":42}}}"""
        val args = McpProtocol.parseToolArguments(body)
        assertEquals("/tmp/x", args["file_path"])
        assertEquals("42", args["line"])
    }

    @Test
    fun `nested object value is preserved as raw JSON string`() {
        val body = """{"method":"tools/call","params":{"name":"request_permission","arguments":{"tool_name":"Bash","input":{"command":"ls","description":"list"}}}}"""
        val args = McpProtocol.parseToolArguments(body)
        assertEquals("Bash", args["tool_name"])
        val input = args["input"] ?: error("expected input in args, got $args")
        assertTrue("input should be JSON object text, got '$input'", input.startsWith("{") && input.endsWith("}"))
        assertTrue("input should contain 'command' key, got '$input'", input.contains("\"command\""))
        assertTrue("input should contain 'ls' value, got '$input'", input.contains("\"ls\""))
        assertTrue("input should contain 'description' key, got '$input'", input.contains("\"description\""))
    }

    @Test
    fun `nested array value is preserved as raw JSON string`() {
        val body = """{"method":"tools/call","params":{"name":"MultiEdit","arguments":{"file_path":"/x.kt","edits":[{"old_string":"a","new_string":"b"}]}}}"""
        val args = McpProtocol.parseToolArguments(body)
        assertEquals("/x.kt", args["file_path"])
        val edits = args["edits"] ?: error("expected edits in args, got $args")
        assertTrue("edits should be JSON array text, got '$edits'", edits.startsWith("[") && edits.endsWith("]"))
        assertTrue(edits.contains("\"old_string\""))
    }

    @Test
    fun `nested object containing strings with braces is not split prematurely`() {
        val body = """{"method":"tools/call","params":{"name":"Bash","arguments":{"input":{"command":"echo '}'"}}}}"""
        val args = McpProtocol.parseToolArguments(body)
        val input = args["input"] ?: error("expected input, got $args")
        assertTrue("input should balance braces around quoted '}', got '$input'", input.startsWith("{") && input.endsWith("}"))
        assertTrue(input.contains("echo"))
    }

    @Test
    fun `dispatched through router the handler receives nested input`() {
        val router = McpToolRouter()
        router.register(
            name = "request_permission",
            description = "test",
            properties = listOf(
                Triple("tool_name", "string", "n"),
                Triple("input", "string", "i"),
            ),
            required = listOf("tool_name"),
            handler = { args ->
                McpToolRouter.ToolResult("tool_name=${args["tool_name"]} input=${args["input"]}")
            },
        )
        val body = """{"method":"tools/call","params":{"name":"request_permission","arguments":{"tool_name":"Bash","input":{"command":"ls"}}}}"""
        val arguments = McpProtocol.parseToolArguments(body)
        val result = router.dispatch("request_permission", arguments)
        assertTrue("result text should expose raw input JSON, got '${result.text}'",
            result.text.contains("input={\"command\":\"ls\"}"))
    }
}
