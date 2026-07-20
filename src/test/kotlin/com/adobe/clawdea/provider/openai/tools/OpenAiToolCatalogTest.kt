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
package com.adobe.clawdea.provider.openai.tools

import com.adobe.clawdea.mcp.McpToolRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiToolCatalogTest {

    @Test
    fun `catalog converts MCP definitions to OpenAI function tools`() {
        val router = McpToolRouter().apply {
            register("find_files", "Find files", listOf(Triple("pattern", "string", "Glob")), listOf("pattern")) {
                McpToolRouter.ToolResult("ok")
            }
        }
        val tool = OpenAiToolCatalog(router.definitions(), emptyList()).definitions().single()
        assertEquals("find_files", tool.function.name)
        val required = tool.function.parameters.getAsJsonArray("required")
        assertEquals(1, required.size())
        assertEquals("pattern", required[0].asString)
    }

    @Test
    fun `catalog dispatch validates required parameters`() {
        val router = McpToolRouter().apply {
            register("find_files", "Find files", listOf(Triple("pattern", "string", "Glob")), listOf("pattern")) {
                McpToolRouter.ToolResult("found: ${it["pattern"]}")
            }
        }
        val catalog = OpenAiToolCatalog(router.definitions(), emptyList())

        // Missing required parameter
        val result1 = catalog.dispatch("tool-id-1", "find_files", "{}")
        assertTrue(result1.isError)
        assertTrue(result1.content.contains("missing required"))

        // Valid call
        val result2 = catalog.dispatch("tool-id-2", "find_files", """{"pattern":"*.kt"}""")
        assertEquals(false, result2.isError)
        assertEquals("found: *.kt", result2.content)
    }

    @Test
    fun `catalog dispatch rejects unknown tools`() {
        val router = McpToolRouter()
        val catalog = OpenAiToolCatalog(router.definitions(), emptyList())

        val result = catalog.dispatch("tool-id-1", "unknown_tool", "{}")
        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown tool"))
    }

    @Test
    fun `catalog dispatch rejects malformed JSON`() {
        val router = McpToolRouter().apply {
            register("find_files", "Find files", listOf(Triple("pattern", "string", "Glob")), listOf("pattern")) {
                McpToolRouter.ToolResult("ok")
            }
        }
        val catalog = OpenAiToolCatalog(router.definitions(), emptyList())

        val result = catalog.dispatch("tool-id-1", "find_files", "not-json")
        assertTrue(result.isError)
        assertTrue(result.content.contains("malformed") || result.content.contains("JSON"))
    }

    @Test
    fun `catalog dispatch coerces numeric and boolean args to their string form`() {
        val router = McpToolRouter().apply {
            register(
                "make_thing",
                "Make a thing",
                listOf(
                    Triple("count", "integer", "How many"),
                    Triple("enabled", "boolean", "On or off"),
                ),
                listOf("count"),
            ) {
                McpToolRouter.ToolResult("count=${it["count"]} enabled=${it["enabled"]}")
            }
        }
        val catalog = OpenAiToolCatalog(router.definitions(), emptyList())

        // Models WILL send numeric/boolean JSON values once tools are advertised.
        val result = catalog.dispatch("tool-id-1", "make_thing", """{"count":42,"enabled":true}""")
        assertEquals(false, result.isError)
        assertEquals("count=42 enabled=true", result.content)
    }

    @Test
    fun `catalog dispatch passes array and object args through as JSON text`() {
        val router = McpToolRouter().apply {
            register("collect", "Collect", listOf(Triple("items", "array", "Items")), listOf("items")) {
                McpToolRouter.ToolResult("items=${it["items"]}")
            }
        }
        val catalog = OpenAiToolCatalog(router.definitions(), emptyList())

        val result = catalog.dispatch("tool-id-1", "collect", """{"items":["a","b"]}""")
        assertEquals(false, result.isError)
        assertEquals("""items=["a","b"]""", result.content)
    }

    @Test
    fun `host tool definitions advertise Bash and apply_patch with correct schemas`() {
        val hostDefs = OpenAiToolCatalog.hostToolDefinitions()
        val byName = hostDefs.associateBy { it.function.name }

        val bash = byName["Bash"]
        assertTrue(bash != null)
        assertEquals("command", bash!!.function.parameters.getAsJsonArray("required").single().asString)

        val patch = byName["apply_patch"]
        assertTrue(patch != null)
        val patchRequired = patch!!.function.parameters.getAsJsonArray("required").map { it.asString }.toSet()
        assertEquals(setOf("file_path", "original_content", "proposed_content"), patchRequired)
    }
}
