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

class SlashCompletionProviderTest {

    private val provider = SlashCompletionProvider()

    @Test
    fun `extractSlashPrefix returns prefix when slash at position 0`() {
        assertEquals("/bra", provider.extractSlashPrefix("/bra", 4))
    }

    @Test
    fun `extractSlashPrefix returns null when slash is not at position 0`() {
        assertNull(provider.extractSlashPrefix("hello /bra", 10))
    }

    @Test
    fun `extractSlashPrefix returns just slash when typed alone`() {
        assertEquals("/", provider.extractSlashPrefix("/", 1))
    }

    @Test
    fun `extractSlashPrefix returns null for empty text`() {
        assertNull(provider.extractSlashPrefix("", 0))
    }

    @Test
    fun `extractSlashPrefix returns null when text does not start with slash`() {
        assertNull(provider.extractSlashPrefix("hello", 5))
    }

    @Test
    fun `filterCommands matches commands by prefix`() {
        val commands = listOf(
            CommandInfo("/brainstorm", "Brainstorm ideas", CommandCategory.SKILL),
            CommandInfo("/branches", "List branches", CommandCategory.LOCAL),
            CommandInfo("/clear", "Clear chat", CommandCategory.LOCAL),
        )

        val results = provider.filterCommands(commands, "/bra")
        assertEquals(2, results.size)
        assertTrue(results.any { it.name == "/brainstorm" })
        assertTrue(results.any { it.name == "/branches" })
    }

    @Test
    fun `filterCommands returns all when prefix is just slash`() {
        val commands = listOf(
            CommandInfo("/clear", "Clear chat", CommandCategory.LOCAL),
            CommandInfo("/brainstorm", "Brainstorm", CommandCategory.SKILL),
        )

        val results = provider.filterCommands(commands, "/")
        assertEquals(2, results.size)
    }

    @Test
    fun `filterCommands is case-insensitive`() {
        val commands = listOf(
            CommandInfo("/Brainstorm", "Brainstorm", CommandCategory.SKILL),
        )

        val results = provider.filterCommands(commands, "/bra")
        assertEquals(1, results.size)
    }

    @Test
    fun `filterCommands only matches on name prefix`() {
        val commands = listOf(
            CommandInfo("/tdd", "Test-driven development", CommandCategory.SKILL),
            CommandInfo("/clear", "Clear chat", CommandCategory.LOCAL),
        )

        val results = provider.filterCommands(commands, "/test")
        assertEquals(0, results.size)
    }

    @Test
    fun `groupByCategory groups commands correctly`() {
        val commands = listOf(
            CommandInfo("/brainstorm", "Brainstorm", CommandCategory.SKILL),
            CommandInfo("/clear", "Clear chat", CommandCategory.LOCAL),
            CommandInfo("/callers", "Find callers", CommandCategory.INDEX),
            CommandInfo("/debug", "Debug", CommandCategory.SKILL),
        )

        val grouped = provider.groupByCategory(commands)
        assertEquals(3, grouped.size)
        assertEquals(2, grouped[CommandCategory.SKILL]?.size)
        assertEquals(1, grouped[CommandCategory.LOCAL]?.size)
        assertEquals(1, grouped[CommandCategory.INDEX]?.size)
    }
}
