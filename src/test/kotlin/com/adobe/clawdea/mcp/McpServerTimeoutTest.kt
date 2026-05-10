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
package com.adobe.clawdea.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerTimeoutTest {

    @Test
    fun `user interactive tools do not use the generic timeout`() {
        listOf(
            "request_permission",
            "propose_edit",
            "propose_write",
            "propose_multi_edit",
            "propose_notebook_edit",
        ).forEach { toolName ->
            assertFalse("$toolName should not use generic timeout", McpServer.shouldUseGenericToolTimeout(toolName))
        }
    }

    @Test
    fun `non interactive tools still use the generic timeout`() {
        listOf("find_files", "search_text").forEach { toolName ->
            assertTrue("$toolName should use generic timeout", McpServer.shouldUseGenericToolTimeout(toolName))
        }
    }
}
