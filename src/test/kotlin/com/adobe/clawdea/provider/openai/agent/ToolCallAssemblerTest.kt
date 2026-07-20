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
package com.adobe.clawdea.provider.openai.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallAssemblerTest {
    @Test
    fun `assembler completes interleaved tool calls by index`() {
        val assembler = ToolCallAssembler()
        assembler.accept(AgentStreamEvent.ToolFragment(0, "a", "find_files", """{"pattern":"""))
        assembler.accept(AgentStreamEvent.ToolFragment(1, "b", "get_context", "{}"))
        assembler.accept(AgentStreamEvent.ToolFragment(0, null, null, """"*.kt"}"""))
        assertEquals(listOf("a", "b"), assembler.completed().map { it.id })
        assertEquals("""{"pattern":"*.kt"}""", assembler.completed()[0].argumentsJson)
    }

    @Test
    fun `assembler handles single complete tool call`() {
        val assembler = ToolCallAssembler()
        assembler.accept(AgentStreamEvent.ToolFragment(0, "call-1", "read", """{"file":"test.kt"}"""))
        val result = assembler.completed()
        assertEquals(1, result.size)
        assertEquals("call-1", result[0].id)
        assertEquals("read", result[0].name)
        assertEquals("""{"file":"test.kt"}""", result[0].argumentsJson)
    }

    @Test
    fun `assembler returns empty list when no fragments accepted`() {
        val assembler = ToolCallAssembler()
        assertEquals(emptyList<AgentToolCall>(), assembler.completed())
    }

    @Test
    fun `assembler handles multiple continuations for same index`() {
        val assembler = ToolCallAssembler()
        assembler.accept(AgentStreamEvent.ToolFragment(0, "x", "search", "{\"q\":\""))
        assembler.accept(AgentStreamEvent.ToolFragment(0, null, null, "test"))
        assembler.accept(AgentStreamEvent.ToolFragment(0, null, null, "\"}"))
        val result = assembler.completed()
        assertEquals(1, result.size)
        assertEquals("{\"q\":\"test\"}", result[0].argumentsJson)
    }
}
