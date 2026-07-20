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
package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.provider.openai.agent.AgentToolCall
import com.adobe.clawdea.provider.openai.agent.AgentToolExecutor
import com.adobe.clawdea.provider.openai.tools.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarianExecutorGuardTest {

    @Test
    fun allowlisted_tool_delegates() {
        var delegateCalled = false
        val delegate = object : AgentToolExecutor {
            override fun execute(toolCall: AgentToolCall): ToolExecutionResult {
                delegateCalled = true
                return ToolExecutionResult(toolCall.id, "success", isError = false)
            }
        }
        val executor = readOnlyExecutor(delegate)
        val result = executor.execute(AgentToolCall("id1", "Read", "{}"))

        assertTrue("Allowlisted tool should delegate", delegateCalled)
        assertFalse("Allowlisted tool should return success", result.isError)
        assertEquals("success", result.content)
    }

    @Test
    fun bash_blocked() {
        var delegateCalled = false
        val delegate = object : AgentToolExecutor {
            override fun execute(toolCall: AgentToolCall): ToolExecutionResult {
                delegateCalled = true
                return ToolExecutionResult(toolCall.id, "should not reach", isError = false)
            }
        }
        val executor = readOnlyExecutor(delegate)
        val result = executor.execute(AgentToolCall("id2", "Bash", "{}"))

        assertFalse("Bash should not delegate", delegateCalled)
        assertTrue("Bash should return error", result.isError)
        assertTrue("Error message should mention permission", result.content.contains("not permitted"))
    }

    @Test
    fun propose_write_blocked() {
        var delegateCalled = false
        val delegate = object : AgentToolExecutor {
            override fun execute(toolCall: AgentToolCall): ToolExecutionResult {
                delegateCalled = true
                return ToolExecutionResult(toolCall.id, "should not reach", isError = false)
            }
        }
        val executor = readOnlyExecutor(delegate)
        val result = executor.execute(AgentToolCall("id3", "propose_write", "{}"))

        assertFalse("propose_write should not delegate", delegateCalled)
        assertTrue("propose_write should return error", result.isError)
        assertTrue("Error message should mention permission", result.content.contains("not permitted"))
    }

    @Test
    fun apply_patch_blocked() {
        var delegateCalled = false
        val delegate = object : AgentToolExecutor {
            override fun execute(toolCall: AgentToolCall): ToolExecutionResult {
                delegateCalled = true
                return ToolExecutionResult(toolCall.id, "should not reach", isError = false)
            }
        }
        val executor = readOnlyExecutor(delegate)
        val result = executor.execute(AgentToolCall("id4", "apply_patch", "{}"))

        assertFalse("apply_patch should not delegate", delegateCalled)
        assertTrue("apply_patch should return error", result.isError)
        assertTrue("Error message should mention permission", result.content.contains("not permitted"))
    }

    @Test
    fun all_allowlisted_tools_delegate() {
        val allowedTools = listOf(
            "Read", "read_wiki_page", "search_text", "find_files", "find_symbol",
            "find_usages", "find_callers", "resolve_symbol", "get_diagnostics",
            "record_wiki_suggestion"
        )

        for (toolName in allowedTools) {
            var delegateCalled = false
            val delegate = object : AgentToolExecutor {
                override fun execute(toolCall: AgentToolCall): ToolExecutionResult {
                    delegateCalled = true
                    return ToolExecutionResult(toolCall.id, "ok", isError = false)
                }
            }
            val executor = readOnlyExecutor(delegate)
            val result = executor.execute(AgentToolCall("id_$toolName", toolName, "{}"))

            assertTrue("$toolName should delegate", delegateCalled)
            assertFalse("$toolName should succeed", result.isError)
        }
    }
}
