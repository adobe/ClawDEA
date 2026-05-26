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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Snapshot test for [CollidingToolNames].
 *
 * The set is the canonical drop list when the JetBrains MCP plugin is detected.
 * Any change to it should be a deliberate, code-reviewed action — this test
 * forces the change to be acknowledged here.
 */
class CollidingToolNamesTest {

    @Test
    fun `collision set matches the documented drop list`() {
        assertEquals(
            setOf("search_text", "find_files", "resolve_symbol", "get_diagnostics"),
            CollidingToolNames,
        )
    }
}
