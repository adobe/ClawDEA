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
package com.adobe.clawdea.cli

import org.junit.Assert.*
import org.junit.Test

class CliProcessDisallowedToolsTest {

    @Test
    fun `returns null when MCP is not available`() {
        assertNull(CliProcess.buildDisallowedTools(mcpAvailable = false))
    }

    @Test
    fun `disallows only Grep and Glob when MCP available`() {
        val result = CliProcess.buildDisallowedTools(mcpAvailable = true)
        assertEquals("Grep,Glob", result)
    }

    @Test
    fun `does not disallow file-edit tools (Layer 2 capture handles them)`() {
        val result = CliProcess.buildDisallowedTools(mcpAvailable = true)!!
        for (tool in listOf("Edit", "Write", "MultiEdit", "NotebookEdit")) {
            assertFalse("expected $tool to NOT be in disallowedTools (Layer 2 capture handles it), got: $result", result.contains(tool))
        }
    }
}
