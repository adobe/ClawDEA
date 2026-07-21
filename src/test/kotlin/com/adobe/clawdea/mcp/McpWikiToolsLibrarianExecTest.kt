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

/**
 * Fixture test for ask_wiki_librarian tiering by WIKI provider.
 *
 * Note: This test suite uses IntelliJ platform fixtures and MUST be run from the
 * IDE test runner. The Claude-subprocess (bedrock/anthropic WIKI) and Codex-subprocess
 * (openai WIKI) branches are not tested here as they would shell out to a real `claude`
 * / `codex` binary; that logic is covered by ClaudeSubprocessLibrarianTest and
 * CodexExecLibrarianTest in the knowledge.wiki package, and the routing by
 * LibrarianExecutionTest.
 */
class McpWikiToolsLibrarianExecTest : BasePlatformTestCase() {
    private fun setWiki(provider: String) {
        val s = ClawDEASettings.getInstance()
        s.state.enableWikiLibrarian = true
        RoleSelectionStore(s).set(
            AgentRole.WIKI,
            AgentSelection(provider, if (provider == "openai-compatible") "p" else null, "m")
        )
    }

    private fun dispatch(): McpToolRouter.ToolResult {
        val router = McpToolRouter()
        McpWikiTools(project).registerAll(router)
        return router.dispatch(McpWikiTools.ASK_LIBRARIAN_TOOL_NAME, mapOf("question" to "how does X work?"))
    }

    fun test_openai_wiki_without_profile_still_reports_profile_error() {
        setWiki("openai-compatible")
        // No credential/profile configured in the test env → agentic path reports a profile/credential error,
        // NOT a crash. (Exact text depends on ProfileStore.resolve; assert it is an error result.)
        val r = dispatch()
        assertTrue(r.isError)
    }
}
