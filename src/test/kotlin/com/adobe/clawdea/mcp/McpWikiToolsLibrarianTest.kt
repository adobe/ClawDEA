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

import com.adobe.clawdea.provider.AgentRole
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.RoleSelectionStore
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class McpWikiToolsLibrarianTest : BasePlatformTestCase() {

    private fun setWiki(provider: String) {
        val s = ClawDEASettings.getInstance()
        s.state.enableWikiLibrarian = true
        RoleSelectionStore(s).set(AgentRole.WIKI, AgentSelection(provider, if (provider == "openai-compatible") "p" else null, "m"))
    }

    fun test_tool_registered_when_wiki_is_openai_compatible() {
        setWiki("openai-compatible")
        val router = McpToolRouter()
        McpWikiTools(project).registerAll(router)
        assertTrue(router.definitions().any { it.name == McpWikiTools.ASK_LIBRARIAN_TOOL_NAME })
    }

    fun test_tool_absent_when_wiki_is_claude() {
        setWiki("bedrock")
        val router = McpToolRouter()
        McpWikiTools(project).registerAll(router)
        assertFalse(router.definitions().any { it.name == McpWikiTools.ASK_LIBRARIAN_TOOL_NAME })
    }
}
