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

    // --- buildJson: both subagents, both with prompt/description/tools ---

    @Test fun `buildJson includes both subagents`() {
        val json = WikiAgentsArg.buildJson()
        val root = JsonParser.parseString(json).asJsonObject
        assertTrue("librarian present", root.has("wiki-librarian"))
        assertTrue("author present", root.has("wiki-author"))
    }

    @Test fun `librarian inner object has description prompt tools`() {
        val root = JsonParser.parseString(WikiAgentsArg.buildJson()).asJsonObject
        val inner = root.getAsJsonObject("wiki-librarian")
        assertTrue(inner.has("description"))
        assertTrue(inner.has("prompt"))
        assertTrue(inner.has("tools"))
    }

    @Test fun `librarian tools allowlist is read-only — no propose_ tools`() {
        val root = JsonParser.parseString(WikiAgentsArg.buildJson()).asJsonObject
        val tools = root.getAsJsonObject("wiki-librarian").getAsJsonArray("tools")
        val toolNames = (0 until tools.size()).map { tools[it].asString }
        assertTrue("librarian must have record_wiki_suggestion",
            "mcp__clawdea-intellij__record_wiki_suggestion" in toolNames)
        assertFalse("librarian must NOT have propose_write",
            "mcp__clawdea-intellij__propose_write" in toolNames)
        assertFalse("librarian must NOT have propose_edit",
            "mcp__clawdea-intellij__propose_edit" in toolNames)
    }

    @Test fun `author tools allowlist has Edit and Write and lacks record_wiki_suggestion`() {
        val root = JsonParser.parseString(WikiAgentsArg.buildJson()).asJsonObject
        val tools = root.getAsJsonObject("wiki-author").getAsJsonArray("tools")
        val toolNames = (0 until tools.size()).map { tools[it].asString }
        // The auto-update path runs unattended; propose_* tools block on a diff
        // dialog that nobody is watching. The author writes via Edit/Write.
        assertTrue("author must have Edit", "Edit" in toolNames)
        assertTrue("author must have Write", "Write" in toolNames)
        assertFalse("author must NOT have propose_write",
            "mcp__clawdea-intellij__propose_write" in toolNames)
        assertFalse("author must NOT have propose_edit",
            "mcp__clawdea-intellij__propose_edit" in toolNames)
        assertFalse("author must NOT have record_wiki_suggestion",
            "mcp__clawdea-intellij__record_wiki_suggestion" in toolNames)
    }

    @Test fun `author prompt has placeholders substituted`() {
        val root = JsonParser.parseString(WikiAgentsArg.buildJson()).asJsonObject
        val prompt = root.getAsJsonObject("wiki-author").get("prompt").asString
        assertFalse("placeholder should be substituted", prompt.contains("{{wiki-page-invariant}}"))
        assertFalse("placeholder should be substituted", prompt.contains("{{wiki-page-navigation}}"))
        // The substituted templates begin with "# Invariant-first wiki page template"
        // and "# Navigation wiki page template" respectively.
        assertTrue("invariant template substituted", prompt.contains("Invariant-first wiki page template"))
        assertTrue("navigation template substituted", prompt.contains("Navigation wiki page template"))
    }

    // --- buildAuthorOnlyJson ---

    @Test fun `buildAuthorOnlyJson contains only the author`() {
        val root = JsonParser.parseString(WikiAgentsArg.buildAuthorOnlyJson()).asJsonObject
        assertTrue("author present", root.has("wiki-author"))
        assertFalse("librarian absent", root.has("wiki-librarian"))
    }

    // --- buildJson with optional librarian model ---

    @Test
    fun buildJson_emits_model_for_librarian_when_provided() {
        val json = JsonParser.parseString(WikiAgentsArg.buildJson("bedrock-model-x")).asJsonObject
        val librarian = json.getAsJsonObject("wiki-librarian")
        assertEquals("bedrock-model-x", librarian.get("model").asString)
        // author entry must NOT get the librarian model
        assertFalse(json.getAsJsonObject("wiki-author").has("model"))
    }

    @Test
    fun buildJson_omits_model_when_blank_preserving_legacy_output() {
        val json = JsonParser.parseString(WikiAgentsArg.buildJson()).asJsonObject
        assertFalse(json.getAsJsonObject("wiki-librarian").has("model"))
        assertEquals(WikiAgentsArg.buildJson(), WikiAgentsArg.buildJson(""))
    }

    @Test
    fun librarianPromptBody_returns_substituted_nonblank_body() {
        val body = WikiAgentsArg.librarianPromptBody()
        assertTrue(body.isNotBlank())
        assertFalse(body.contains("{{")) // placeholders substituted
    }
}
