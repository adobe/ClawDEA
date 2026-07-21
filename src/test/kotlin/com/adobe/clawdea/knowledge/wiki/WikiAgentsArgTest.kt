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

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WikiAgentsArgTest {

    // --- frontmatter parser (unchanged contract from WikiLibrarianAgentArg) ---

    @Test fun `parse extracts name description tools and body`() {
        val text = """
            |---
            |name: my-agent
            |description: One line description here.
            |tools: Read, mcp__x__find_symbol, mcp__x__read_file
            |---
            |
            |Body line one.
            |Body line two.
        """.trimMargin()
        val parsed = WikiAgentsArg.parse(text)
        assertEquals("my-agent", parsed.name)
        assertEquals("One line description here.", parsed.description)
        assertEquals(listOf("Read", "mcp__x__find_symbol", "mcp__x__read_file"), parsed.tools)
        assertEquals("Body line one.\nBody line two.", parsed.body)
    }

    @Test fun `parse rejects missing name`() {
        try {
            WikiAgentsArg.parse("---\ndescription: d\n---\nbody")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("'name'"))
        }
    }

    // --- buildAuthorOnlyJson ---

    @Test fun `buildAuthorOnlyJson contains only the author`() {
        val root = JsonParser.parseString(WikiAgentsArg.buildAuthorOnlyJson()).asJsonObject
        assertTrue("author present", root.has("wiki-author"))
        assertFalse("librarian absent", root.has("wiki-librarian"))
    }

    @Test
    fun librarianPromptBody_returns_substituted_nonblank_body() {
        val body = WikiAgentsArg.librarianPromptBody()
        assertTrue(body.isNotBlank())
        assertFalse(body.contains("{{")) // placeholders substituted
    }
}
