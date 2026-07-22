/*
 * Copyright 2025 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.cli

import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CodexInstructionsLibrarianTest : BasePlatformTestCase() {
    fun test_codex_instructions_name_the_mcp_tool_not_subagent() {
        ClawDEASettings.getInstance().state.enableWikiLibrarian = true
        val text = CodexInstructions.build(project, emptyList())
        assertTrue(text.contains("ask_wiki_librarian"))
        assertFalse(text.contains("subagent_type=\"wiki-librarian\""))
    }
}
