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
package com.adobe.clawdea.commands

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CommandRegistryTest {

    private lateinit var registry: CommandRegistry

    private fun makeHandler(name: String, category: CommandCategory = CommandCategory.LOCAL): CommandHandler {
        return object : CommandHandler {
            override val info = CommandInfo(name, "desc for $name", category)
            override fun execute(args: String, context: CommandContext) {}
        }
    }

    @Before
    fun setUp() {
        registry = CommandRegistry()
    }

    @Test
    fun `register and resolve a command`() {
        val handler = makeHandler("/clear")
        registry.register("/clear", handler)

        val match = registry.resolve("/clear")
        assertNotNull(match)
        assertSame(handler, match!!.handler)
        assertEquals("", match.args)
    }

    @Test
    fun `resolve parses args after command name`() {
        val handler = makeHandler("/mode")
        registry.register("/mode", handler)

        val match = registry.resolve("/mode auto")
        assertNotNull(match)
        assertEquals("auto", match!!.args)
    }

    @Test
    fun `resolve returns null for unregistered command`() {
        assertNull(registry.resolve("/nonexistent"))
    }

    @Test
    fun `resolve is case-insensitive`() {
        val handler = makeHandler("/clear")
        registry.register("/clear", handler)

        assertNotNull(registry.resolve("/CLEAR"))
        assertNotNull(registry.resolve("/Clear"))
    }

    @Test
    fun `resolve handles non-command text`() {
        assertNull(registry.resolve("hello world"))
    }

    @Test
    fun `unregister removes a command`() {
        val handler = makeHandler("/clear")
        registry.register("/clear", handler)
        assertNotNull(registry.resolve("/clear"))

        registry.unregister("/clear")
        assertNull(registry.resolve("/clear"))
    }

    @Test
    fun `allCommands returns all registered command infos`() {
        registry.register("/clear", makeHandler("/clear"))
        registry.register("/stop", makeHandler("/stop"))

        val all = registry.allCommands()
        assertEquals(2, all.size)
        assertTrue(all.any { it.name == "/clear" })
        assertTrue(all.any { it.name == "/stop" })
    }

    @Test
    fun `register multiple aliases for same handler`() {
        val handler = makeHandler("/brainstorming")
        registry.register("/brainstorming", handler)
        registry.register("/superpowers:brainstorming", handler)

        assertNotNull(registry.resolve("/brainstorming"))
        assertNotNull(registry.resolve("/superpowers:brainstorming"))
    }

    @Test
    fun `resolve trims whitespace from input`() {
        val handler = makeHandler("/clear")
        registry.register("/clear", handler)

        assertNotNull(registry.resolve("  /clear  "))
    }
}
