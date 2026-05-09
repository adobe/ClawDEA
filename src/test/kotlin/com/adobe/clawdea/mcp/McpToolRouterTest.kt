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

class McpToolRouterTest {

    @Test
    fun `dispatch calls registered handler and returns result`() {
        val router = McpToolRouter()
        router.register(
            name = "echo",
            description = "Echo back",
            properties = listOf(Triple("msg", "string", "The message")),
            required = listOf("msg"),
            handler = { args -> McpToolRouter.ToolResult(args["msg"] ?: "") },
        )

        val result = router.dispatch("echo", mapOf("msg" to "hello"))
        assertEquals("hello", result.text)
        assertFalse(result.isError)
    }

    @Test
    fun `dispatch returns error for unknown tool`() {
        val router = McpToolRouter()
        val result = router.dispatch("nonexistent", emptyMap())
        assertTrue(result.isError)
        assertTrue(result.text.contains("Unknown tool"))
    }

    @Test
    fun `dispatch catches handler exception and returns error`() {
        val router = McpToolRouter()
        router.register(
            name = "boom",
            description = "Always throws",
            properties = emptyList(),
            handler = { throw RuntimeException("kaboom") },
        )

        val result = router.dispatch("boom", emptyMap())
        assertTrue(result.isError)
        assertTrue(result.text.contains("kaboom"))
    }

    @Test
    fun `toolsListJson includes all registered tools`() {
        val router = McpToolRouter()
        router.register(
            name = "tool_a",
            description = "First tool",
            properties = listOf(Triple("x", "string", "param x")),
            handler = { McpToolRouter.ToolResult("ok") },
        )
        router.register(
            name = "tool_b",
            description = "Second tool",
            properties = emptyList(),
            handler = { McpToolRouter.ToolResult("ok") },
        )

        val json = router.toolsListJson()
        assertTrue(json.contains("tool_a"))
        assertTrue(json.contains("tool_b"))
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
    }

    @Test
    fun `later registration overwrites earlier one`() {
        val router = McpToolRouter()
        router.register(
            name = "dup",
            description = "v1",
            properties = emptyList(),
            handler = { McpToolRouter.ToolResult("v1") },
        )
        router.register(
            name = "dup",
            description = "v2",
            properties = emptyList(),
            handler = { McpToolRouter.ToolResult("v2") },
        )

        val result = router.dispatch("dup", emptyMap())
        assertEquals("v2", result.text)
    }
}
