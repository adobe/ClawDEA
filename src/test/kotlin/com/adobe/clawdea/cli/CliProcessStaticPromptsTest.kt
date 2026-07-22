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

import com.adobe.clawdea.knowledge.prompts.PromptResource
import org.junit.Assert.*
import org.junit.Test

class CliProcessStaticPromptsTest {

    @Test
    fun `mcp-system-prompt resource loads with expected sentinels`() {
        val text = PromptResource.load("mcp-system-prompt")
        assertTrue(text.contains("You're running inside IntelliJ."))
        assertTrue(text.contains("Code-search tool routing"))
        assertTrue(text.contains("debugger to observe."))
    }

    @Test
    fun `edit-review-prompt resource loads with expected sentinels`() {
        val text = PromptResource.load("edit-review-prompt")
        assertTrue(text.contains("File-edit routing:"))
        assertTrue(text.contains("propose_multi_edit"))
        assertTrue(text.contains("any non-trivial edit"))
    }
}
