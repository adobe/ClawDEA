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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpWorkspaceToolsTest {

    @Test fun `list tool name is stable`() {
        assertEquals("list_workspace_repos", McpWorkspaceTools.LIST_TOOL_NAME)
    }

    @Test fun `list description mentions workspace and repos`() {
        assertTrue(McpWorkspaceTools.LIST_TOOL_DESCRIPTION.contains("workspace", ignoreCase = true))
        assertTrue(McpWorkspaceTools.LIST_TOOL_DESCRIPTION.contains("repo", ignoreCase = true))
    }

    @Test fun `read sibling wiki tool name is stable`() {
        assertEquals("read_sibling_wiki", McpWorkspaceTools.READ_SIBLING_WIKI_TOOL_NAME)
    }

    @Test fun `read sibling wiki description mentions sibling and wiki`() {
        assertTrue(McpWorkspaceTools.READ_SIBLING_WIKI_TOOL_DESCRIPTION.contains("sibling", ignoreCase = true))
        assertTrue(McpWorkspaceTools.READ_SIBLING_WIKI_TOOL_DESCRIPTION.contains("wiki", ignoreCase = true))
    }

    @Test fun `read sibling repo state tool name is stable`() {
        assertEquals("read_sibling_repo_state", McpWorkspaceTools.READ_SIBLING_REPO_STATE_TOOL_NAME)
    }

    @Test fun `read sibling repo state description mentions REPO_STATE`() {
        assertTrue(
            McpWorkspaceTools.READ_SIBLING_REPO_STATE_TOOL_DESCRIPTION
                .contains("REPO_STATE", ignoreCase = true)
        )
    }
}
