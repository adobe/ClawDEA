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

class McpWikiToolsTest {
    @Test fun `tool name constants are stable`() {
        assertEquals("read_wiki_page", McpWikiTools.READ_TOOL_NAME)
        assertEquals("search_wiki", McpWikiTools.SEARCH_TOOL_NAME)
        assertEquals("record_wiki_suggestion", McpWikiTools.RECORD_SUGGESTION_TOOL_NAME)
    }

    @Test fun `read description mentions concept and wiki`() {
        assertTrue(McpWikiTools.READ_TOOL_DESCRIPTION.contains("wiki", ignoreCase = true))
        assertTrue(McpWikiTools.READ_TOOL_DESCRIPTION.contains("concept", ignoreCase = true))
    }

    @Test fun `search description mentions search and wiki`() {
        assertTrue(McpWikiTools.SEARCH_TOOL_DESCRIPTION.contains("search", ignoreCase = true))
        assertTrue(McpWikiTools.SEARCH_TOOL_DESCRIPTION.contains("wiki", ignoreCase = true))
    }

    @Test fun `record-suggestion description names the three kinds`() {
        val desc = McpWikiTools.RECORD_SUGGESTION_TOOL_DESCRIPTION
        assertTrue(desc.contains("missingConcept"))
        assertTrue(desc.contains("staleConcept"))
        assertTrue(desc.contains("incompleteConcept"))
        assertTrue(desc.contains("wiki-librarian"))
    }
}
