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
package com.adobe.clawdea.mcp.coexistence

import com.adobe.clawdea.mcp.McpToolRouter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerCollisionFilterTest {

    private fun populatedRouter(): McpToolRouter {
        val router = McpToolRouter()
        // Names that overlap with JetBrains.
        listOf("search_text", "find_files", "resolve_symbol", "get_diagnostics")
            .forEach { name ->
                router.register(
                    name = name,
                    description = "stub",
                    properties = emptyList(),
                    handler = { McpToolRouter.ToolResult("ok") },
                )
            }
        // Names that must always be kept.
        listOf("find_callers", "find_usages", "propose_edit", "debug_launch")
            .forEach { name ->
                router.register(
                    name = name,
                    description = "stub",
                    properties = emptyList(),
                    handler = { McpToolRouter.ToolResult("ok") },
                )
            }
        return router
    }

    @Test
    fun `Enabled status drops collision names from toolsListJson`() {
        val router = populatedRouter()
        applyCollisionFilter(router, JetBrainsMcpStatus.Enabled)

        val json = router.toolsListJson()
        CollidingToolNames.forEach { name ->
            assertFalse("$name should not appear when JB MCP is Enabled", json.contains(name))
        }
        assertTrue(json.contains("find_callers"))
        assertTrue(json.contains("find_usages"))
        assertTrue(json.contains("propose_edit"))
        assertTrue(json.contains("debug_launch"))
    }

    @Test
    fun `Disabled status keeps all tools registered`() {
        val router = populatedRouter()
        applyCollisionFilter(router, JetBrainsMcpStatus.Disabled)

        val json = router.toolsListJson()
        CollidingToolNames.forEach { name ->
            assertTrue("$name should remain when JB MCP is Disabled", json.contains(name))
        }
    }

    @Test
    fun `Unknown status keeps all tools registered (fail-open)`() {
        val router = populatedRouter()
        applyCollisionFilter(router, JetBrainsMcpStatus.Unknown)

        val json = router.toolsListJson()
        CollidingToolNames.forEach { name ->
            assertTrue("$name should remain when JB MCP is Unknown", json.contains(name))
        }
    }
}
