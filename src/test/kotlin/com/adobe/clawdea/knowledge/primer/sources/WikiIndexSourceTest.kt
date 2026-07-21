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

    // --- Librarian anchor (enableWikiLibrarian=true) ---
    // Full routing rules live in WIKI_LIBRARIAN_TOOL_PROMPT (CliProcess); the
    // anchor here is the in-context nudge co-located with the TOC.

    @Test fun `anchor is mechanism neutral`() {
        val a = WikiIndexSource.buildLibrarianAnchor()
        assertFalse(a.contains("subagent_type"))
        assertFalse(a.contains("ask_wiki_librarian"))
        assertTrue(a.contains("wiki librarian"))
    }

    @Test fun `librarian anchor explicitly covers change-safety questions`() {
        // The bug we're guarding against: the model self-classifies validation /
        // change-safety questions as out of scope and skips the librarian.
        val anchor = WikiIndexSource.buildLibrarianAnchor()
        assertTrue(anchor.contains("change-safety"))
        assertTrue(anchor.contains("is this safe?"))
        assertTrue(anchor.contains("will this regress"))
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
