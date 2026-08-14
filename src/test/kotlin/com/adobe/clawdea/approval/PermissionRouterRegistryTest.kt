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
package com.adobe.clawdea.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PermissionRouterRegistryTest {

    private fun nameRouter(claimFn: (String, String) -> String?): PermissionRouter =
        object : PermissionRouter {
            override fun claim(toolName: String, inputJson: String): String? = claimFn(toolName, inputJson)
        }

    private fun idRouter(allowed: Set<String>): PermissionRouter =
        object : PermissionRouter {
            override fun claimById(toolUseId: String): Boolean = toolUseId in allowed
            override fun claim(toolName: String, inputJson: String): String? = null
        }

    @Test fun `route returns null when no router claims the call`() {
        val registry = PermissionRouterRegistry()
        registry.register(nameRouter { _, _ -> null }, dispatcher())
        registry.register(nameRouter { _, _ -> null }, dispatcher())
        assertNull(registry.route("Bash", """{"command":"ls"}""", waitMs = 0))
    }

    @Test fun `route picks the dispatcher belonging to the panel that claims the call by name+input`() {
        // The bug we're guarding against: with two open chat tabs, the old
        // "winner-take-last" holder routed every permission card to whichever
        // panel was focused. The registry must instead pick the dispatcher
        // whose router claims the (toolName, inputJson) when CC does not
        // pass tool_use_id (legacy/stdio fallback path).
        val registry = PermissionRouterRegistry()
        val tabAdispatcher = dispatcher()
        val tabBdispatcher = dispatcher()
        registry.register(
            router = nameRouter { name, input -> if (name == "Bash" && input.contains("\"ls\"")) "tu-A1" else null },
            dispatcher = tabAdispatcher,
        )
        registry.register(
            router = nameRouter { name, input -> if (name == "Bash" && input.contains("\"pwd\"")) "tu-B1" else null },
            dispatcher = tabBdispatcher,
        )

        val routed = registry.route("Bash", """{"command":"pwd"}""", toolUseId = "")
        assertNotNull("a router must claim this call", routed)
        assertSame("must be Tab B's dispatcher, not whichever was registered last", tabBdispatcher, routed!!.dispatcher)
        assertEquals("tu-B1", routed.toolUseId)
    }

    @Test fun `route by tool_use_id picks the panel that emitted the matching ToolUse`() {
        // Even when two panels have a Bash call with byte-identical input,
        // the id-based path picks the right one. This is the regression fix
        // for the (toolName, inputJson) keying being byte-fragile across the
        // stream-json / JSON-RPC boundary.
        val registry = PermissionRouterRegistry()
        val tabA = dispatcher()
        val tabB = dispatcher()
        registry.register(idRouter(setOf("tu-A1", "tu-A2")), tabA)
        registry.register(idRouter(setOf("tu-B1")), tabB)

        val routed = registry.route("Bash", """{"command":"ls"}""", toolUseId = "tu-B1")
        assertNotNull(routed)
        assertSame(tabB, routed!!.dispatcher)
        assertEquals("tu-B1", routed.toolUseId)
    }

    @Test fun `unregister removes the router so a closed panel does not steal calls`() {
        val registry = PermissionRouterRegistry()
        val claiming = dispatcher()
        val passive = dispatcher()
        registry.register(nameRouter { _, _ -> "tu-claiming" }, claiming)
        registry.register(nameRouter { _, _ -> null }, passive)
        registry.unregister(claiming)

        assertNull(registry.route("Bash", """{"command":"ls"}""", waitMs = 0))
        assertEquals(1, registry.size())
    }

    @Test fun `route waits briefly for a late claim instead of returning null immediately`() {
        // Reproduces the production race: the MCP request_permission handler
        // arrives on the HTTP thread before the EDT has finished posting the
        // matching ToolUse into the panel's claim map.
        val registry = PermissionRouterRegistry()
        val claiming = dispatcher()
        val readyAfter = System.currentTimeMillis() + 100
        registry.register(
            router = nameRouter { _, _ -> if (System.currentTimeMillis() >= readyAfter) "tu-late" else null },
            dispatcher = claiming,
        )

        val routed = registry.route("Bash", """{"command":"ls"}""", waitMs = 1_000)
        assertNotNull("route must wait for the late claim", routed)
        assertEquals("tu-late", routed!!.toolUseId)
        assertSame(claiming, routed.dispatcher)
    }

    @Test fun `a router that throws does not break routing for the next router`() {
        val registry = PermissionRouterRegistry()
        val tabA = dispatcher()
        val tabB = dispatcher()
        registry.register(nameRouter { _, _ -> error("boom") }, tabA)
        registry.register(nameRouter { _, _ -> "tu-B" }, tabB)

        val routed = registry.route("Bash", """{"command":"ls"}""", waitMs = 0)
        assertNotNull("a throwing router must not stop downstream routing", routed)
        assertSame(tabB, routed!!.dispatcher)
    }

    private fun dispatcher() = PermissionDispatcher(onRender = { _ -> /* unused */ })
}
