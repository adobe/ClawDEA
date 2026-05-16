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
package com.adobe.clawdea.knowledge.primer.sources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiIndexSourceTest {

    // --- Librarian directive (default, enableWikiLibrarian=true) ---

    @Test fun `librarian directive teaches Task delegation`() {
        val directive = WikiIndexSource.buildLibrarianDirective()
        assertTrue(directive.contains("wiki-librarian"))
        assertTrue(directive.contains("Task(subagent_type=\"wiki-librarian\""))
        assertTrue(directive.contains("Hard rule"))
    }

    @Test fun `librarian directive preserves read_wiki_page escape hatch`() {
        val directive = WikiIndexSource.buildLibrarianDirective()
        assertTrue(directive.contains("read_wiki_page"))
    }

    @Test fun `librarian directive does not mention search_wiki`() {
        val directive = WikiIndexSource.buildLibrarianDirective()
        assertFalse(
            "search_wiki should not appear in librarian directive",
            directive.contains("search_wiki"),
        )
    }

    @Test fun `librarian directive carves out the two exceptions`() {
        val directive = WikiIndexSource.buildLibrarianDirective()
        assertTrue(directive.contains("already have a wiki page slug"))
        assertTrue(directive.contains("Purely lexical edits"))
    }

    // --- Legacy directive (enableWikiLibrarian=false) ---

    @Test fun `legacy directive asks for standard markdown wiki links`() {
        val directive = WikiIndexSource.buildLegacyDirective(".claude/wiki", autoUpdate = false)
        assertTrue(directive.contains("Use standard Markdown links between wiki pages:"))
        assertTrue(directive.contains("[Concept](concept.md)"))
        assertTrue(directive.contains("[Concept](concepts/concept.md)"))
        assertTrue(directive.contains("Do not create new `[[concept]]` references"))
    }

    @Test fun `legacy auto-update gap action asks for markdown index links`() {
        val directive = WikiIndexSource.buildLegacyDirective(".claude/wiki", autoUpdate = true)
        assertTrue(directive.contains("[Title](concepts/<slug>.md)"))
    }

    @Test fun `legacy reviewed gap action preserves propose tools`() {
        val directive = WikiIndexSource.buildLegacyDirective(".claude/wiki", autoUpdate = false)
        assertTrue(directive.contains("`propose_write`"))
        assertTrue(directive.contains("`propose_edit`"))
        assertTrue(directive.contains("[Title](concepts/<slug>.md)"))
    }
}
