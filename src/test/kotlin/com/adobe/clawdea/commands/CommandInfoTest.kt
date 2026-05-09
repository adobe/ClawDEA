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
import org.junit.Test

class CommandInfoTest {

    @Test
    fun `CommandInfo stores name description and category`() {
        val info = CommandInfo("/clear", "Clear chat history", CommandCategory.LOCAL)
        assertEquals("/clear", info.name)
        assertEquals("Clear chat history", info.description)
        assertEquals(CommandCategory.LOCAL, info.category)
    }

    @Test
    fun `CommandCategory has all expected values`() {
        val categories = CommandCategory.entries.map { it.name }
        assertTrue(categories.contains("LOCAL"))
        assertTrue(categories.contains("BRIDGE"))
        assertTrue(categories.contains("INDEX"))
        assertTrue(categories.contains("SKILL"))
        assertTrue(categories.contains("DIALOG"))
    }

    @Test
    fun `CommandMatch holds handler and args`() {
        val handler = object : CommandHandler {
            override fun execute(args: String, context: CommandContext) {}
            override val info get() = CommandInfo("/test", "test", CommandCategory.LOCAL)
        }
        val match = CommandMatch(handler, "some args")
        assertEquals("some args", match.args)
        assertSame(handler, match.handler)
    }

    @Test
    fun `CommandContext provides required fields`() {
        val ctx = CommandContext(
            appendHtml = {},
            showNotification = {},
        )
        assertNotNull(ctx.appendHtml)
        assertNotNull(ctx.showNotification)
    }
}
