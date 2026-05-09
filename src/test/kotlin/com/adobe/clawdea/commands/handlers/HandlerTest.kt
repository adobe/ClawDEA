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
package com.adobe.clawdea.commands.handlers

import com.adobe.clawdea.commands.*
import org.junit.Assert.*
import org.junit.Test

class HandlerTest {

    private fun makeContext(
        appendedHtml: MutableList<String> = mutableListOf(),
        notifications: MutableList<String> = mutableListOf(),
    ): CommandContext {
        return CommandContext(
            appendHtml = { appendedHtml.add(it) },
            showNotification = { notifications.add(it) },
        )
    }

    @Test
    fun `LocalHandler executes action and provides correct info`() {
        var executed = false
        val handler = LocalHandler(
            CommandInfo("/clear", "Clear chat", CommandCategory.LOCAL),
        ) { _, _ -> executed = true }

        handler.execute("", makeContext())
        assertTrue(executed)
        assertEquals("/clear", handler.info.name)
        assertEquals(CommandCategory.LOCAL, handler.info.category)
    }

    @Test
    fun `LocalHandler passes args to action`() {
        var receivedArgs = ""
        val handler = LocalHandler(
            CommandInfo("/mode", "Switch mode", CommandCategory.LOCAL),
        ) { args, _ -> receivedArgs = args }

        handler.execute("plan", makeContext())
        assertEquals("plan", receivedArgs)
    }

    @Test
    fun `BridgeForwardHandler provides correct info`() {
        val handler = BridgeForwardHandler(
            CommandInfo("/cost", "Show cost", CommandCategory.BRIDGE),
        )
        assertEquals("/cost", handler.info.name)
        assertEquals(CommandCategory.BRIDGE, handler.info.category)
    }
}
