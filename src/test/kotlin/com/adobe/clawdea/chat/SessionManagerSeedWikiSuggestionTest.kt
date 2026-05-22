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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [SessionManager.seedWikiSuggestionMessage] — the pure helper
 * driving the three-branch /seed-wiki nudge. Covers the four input
 * combinations (both present, CLAUDE.md missing, wiki missing, both missing)
 * without standing up the full SessionManager (which requires project,
 * bridge, renderers, etc.).
 */
class SessionManagerSeedWikiSuggestionTest {

    @Test
    fun `returns null when both CLAUDE_md and wiki index are present`() {
        val msg = SessionManager.seedWikiSuggestionMessage(
            claudeMdMissing = false,
            wikiIndexMissing = false,
        )
        assertNull("Silent when both are present", msg)
    }

    @Test
    fun `returns CLAUDE_md-specific message when only CLAUDE_md is missing`() {
        val msg = SessionManager.seedWikiSuggestionMessage(
            claudeMdMissing = true,
            wikiIndexMissing = false,
        )
        assertEquals(
            "No CLAUDE.md found in this project. Type /seed-wiki to bootstrap CLAUDE.md and refresh the wiki.",
            msg,
        )
    }

    @Test
    fun `returns wiki-specific message when only wiki index is missing`() {
        val msg = SessionManager.seedWikiSuggestionMessage(
            claudeMdMissing = false,
            wikiIndexMissing = true,
        )
        assertEquals(
            "No wiki found in this project. Type /seed-wiki to bootstrap the wiki for Claude.",
            msg,
        )
    }

    @Test
    fun `returns combined message when both are missing`() {
        val msg = SessionManager.seedWikiSuggestionMessage(
            claudeMdMissing = true,
            wikiIndexMissing = true,
        )
        assertEquals(
            "No CLAUDE.md or wiki found in this project. Type /seed-wiki to bootstrap both.",
            msg,
        )
    }
}
