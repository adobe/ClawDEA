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
package com.adobe.clawdea.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SessionManager.seedWikiSuggestionHtml] — the pure helper
 * driving the three-branch /seed-wiki nudge. Covers the four input
 * combinations (both present, CLAUDE.md missing, wiki missing, both missing)
 * without standing up the full SessionManager (which requires project,
 * bridge, renderers, etc.).
 */
class SessionManagerSeedWikiSuggestionTest {

    @Test
    fun `returns null when both CLAUDE_md and wiki index are present`() {
        val html = SessionManager.seedWikiSuggestionHtml(
            claudeMdMissing = false,
            wikiIndexMissing = false,
        )
        assertNull("Silent when both are present", html)
    }

    @Test
    fun `wraps the message in an info-block div`() {
        val html = SessionManager.seedWikiSuggestionHtml(
            claudeMdMissing = true,
            wikiIndexMissing = true,
        )
        assertNotNull(html)
        assertTrue("expected info-block wrapper, got: $html", html!!.startsWith("""<div class="info-block">"""))
        assertTrue("expected info-block close, got: $html", html.endsWith("</div>"))
    }

    @Test
    fun `renders the seed-wiki text as a clickable run-slash-command link`() {
        // The link is the actionable affordance — clicking it must dispatch
        // through the chat's run-slash-command bridge so the user doesn't
        // have to type the command into the input field.
        val html = SessionManager.seedWikiSuggestionHtml(
            claudeMdMissing = true,
            wikiIndexMissing = true,
        )!!
        assertTrue("missing run-slash-command action, got: $html", html.contains("""data-action="run-slash-command""""))
        assertTrue("missing /seed-wiki target, got: $html", html.contains("""data-slash="/seed-wiki""""))
        assertTrue("missing visible link text, got: $html", html.contains(">/seed-wiki</a>"))
        assertTrue("missing slash-command-link styling, got: $html", html.contains("""class="slash-command-link""""))
    }

    @Test
    fun `branch — CLAUDE_md missing only`() {
        val html = SessionManager.seedWikiSuggestionHtml(
            claudeMdMissing = true,
            wikiIndexMissing = false,
        )!!
        assertTrue(html.contains("No CLAUDE.md found in this project"))
        assertTrue(html.contains("bootstrap CLAUDE.md and refresh the wiki"))
        assertFalse("must not say 'or wiki' in this branch", html.contains("or wiki"))
    }

    @Test
    fun `branch — wiki index missing only`() {
        val html = SessionManager.seedWikiSuggestionHtml(
            claudeMdMissing = false,
            wikiIndexMissing = true,
        )!!
        assertTrue(html.contains("No wiki found in this project"))
        assertTrue(html.contains("bootstrap the wiki for Claude"))
        assertFalse("must not mention CLAUDE.md in this branch", html.contains("CLAUDE.md"))
    }

    @Test
    fun `branch — both missing`() {
        val html = SessionManager.seedWikiSuggestionHtml(
            claudeMdMissing = true,
            wikiIndexMissing = true,
        )!!
        assertTrue(html.contains("No CLAUDE.md or wiki found in this project"))
        assertTrue(html.contains("bootstrap both"))
    }
}
