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

import com.adobe.clawdea.knowledge.drift.DriftEvent
import com.adobe.clawdea.knowledge.drift.SuggestionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class DriftBannerTest {

    private fun codeRename(brokenLink: String = "src/old/Foo.kt") = DriftEvent.CodeRename(
        wikiPage = Paths.get(".claude/wiki/concepts/foo.md"),
        brokenLink = brokenLink,
        suggestedReplacement = "src/new/Foo.kt",
    )

    private fun manifestStale(repoKey: String = "my-repo") = DriftEvent.ManifestStale(
        repoKey = repoKey,
        groupName = "siblings",
        manifestPath = Paths.get(".clawdea-workspace.md"),
        lineHint = 42,
    )

    private fun commitDrift(page: String = "bar.md") = DriftEvent.CommitDrift(
        wikiPage = Paths.get(".claude/wiki/concepts/$page"),
        commitShas = listOf("abc1234"),
        touchedPaths = listOf("src/main/kotlin/Bar.kt"),
        firstObservedAt = "2026-05-17T16:30:00Z",
    )

    private fun wikiSuggestion(title: String = "Add concept for X") = DriftEvent.WikiSuggestion(
        kind = SuggestionKind.missingConcept,
        title = title,
        rationale = "Multiple subsystems reference it.",
        targetFiles = listOf(".claude/wiki/concepts/x.md"),
        sourcePage = null,
        recordedAt = "2026-05-17T16:30:00Z",
    )

    private fun newBanner(captured: MutableList<String> = mutableListOf()): DriftBanner =
        DriftBanner(
            updateHtml = { captured += it },
            onInsertCommand = {},
            onDismissAll = {},
        )

    @Test fun `renderKindCounts emits per-kind line with icon count and label`() {
        val out = renderKindCounts(listOf(codeRename(), codeRename(brokenLink = "x"), commitDrift()))
        assertTrue(out.contains("🔗 2 stale links"))
        assertTrue(out.contains("↻ 1 code changed"))
    }

    @Test fun `renderKindCounts pluralizes for n greater than 1`() {
        val out = renderKindCounts(
            listOf(
                codeRename(),
                codeRename(brokenLink = "x"),
                manifestStale(),
                manifestStale(repoKey = "other"),
                manifestStale(repoKey = "third"),
                commitDrift(),
                commitDrift(page = "y.md"),
                commitDrift(page = "z.md"),
                wikiSuggestion(),
                wikiSuggestion(title = "Other"),
            ),
        )
        assertTrue(out.contains("🔗 2 stale links"))
        assertTrue(out.contains("📋 3 stale manifests"))
        assertTrue(out.contains("↻ 3 code changes"))
        assertTrue(out.contains("✍ 2 suggested updates"))
    }

    @Test fun `renderKindCounts singularizes for n equal to 1`() {
        val out = renderKindCounts(
            listOf(codeRename(), manifestStale(), commitDrift(), wikiSuggestion()),
        )
        assertTrue(out.contains("🔗 1 stale link"))
        assertFalse("expected singular, not plural", out.contains("🔗 1 stale links"))
        assertTrue(out.contains("📋 1 stale manifest"))
        assertFalse(out.contains("📋 1 stale manifests"))
        assertTrue(out.contains("↻ 1 code changed"))
        assertTrue(out.contains("✍ 1 suggested update"))
        assertFalse(out.contains("✍ 1 suggested updates"))
    }

    @Test fun `renderKindCounts omits zero-count kinds`() {
        val out = renderKindCounts(listOf(codeRename(), commitDrift()))
        assertFalse("manifest icon should not appear when no manifest events", out.contains("📋"))
        assertFalse("suggestion icon should not appear when no suggestions", out.contains("✍"))
    }

    @Test fun `renderKindCounts preserves encounter order across kinds`() {
        val out = renderKindCounts(listOf(commitDrift(), codeRename(), manifestStale()))
        val commitIdx = out.indexOf("↻")
        val linkIdx = out.indexOf("🔗")
        val manifestIdx = out.indexOf("📋")
        assertTrue("↻ should come before 🔗 (encounter order)", commitIdx in 0 until linkIdx)
        assertTrue("🔗 should come before 📋 (encounter order)", linkIdx in 0 until manifestIdx)
    }

    @Test fun `renderKindCounts joins kind groups with middle dot separator`() {
        val out = renderKindCounts(listOf(codeRename(), commitDrift()))
        assertTrue("expected ' · ' separator between kinds, got: $out", out.contains(" · "))
    }

    @Test fun `render with events produces banner HTML containing per-kind counts`() {
        val captured = mutableListOf<String>()
        val banner = newBanner(captured)
        banner.setEvents(listOf(codeRename(), codeRename(brokenLink = "x"), commitDrift()))
        val html = captured.last()
        assertTrue("expected wiki: prefix", html.contains("wiki: "))
        assertTrue(html.contains("🔗 2 stale links"))
        assertTrue(html.contains("↻ 1 code changed"))
        assertFalse("legacy 'maintenance suggestion' wording must be gone", html.contains("maintenance suggestion"))
        assertFalse("legacy 'stale ref' wording must be gone", html.contains("stale ref<"))
        assertFalse("legacy 'stale refs' wording must be gone", html.contains("stale refs<"))
    }

    @Test fun `render with empty events hides the banner`() {
        val captured = mutableListOf<String>()
        val banner = newBanner(captured)
        banner.setEvents(emptyList())
        val html = captured.last()
        assertTrue(html.contains("display:none"))
    }

    @Test fun `autoApplyNotificationLines prefixes each line with kind icon`() {
        val banner = newBanner()
        val lines = banner.autoApplyNotificationLines(listOf(codeRename(), manifestStale()))
        assertEquals(2, lines.size)
        assertTrue("first line should start with link icon, got: ${lines[0]}", lines[0].startsWith("🔗 "))
        assertTrue("first line should still contain ✓ marker", lines[0].contains("✓ updated wiki ref"))
        assertTrue("second line should start with clipboard icon, got: ${lines[1]}", lines[1].startsWith("📋 "))
        assertTrue("second line should still contain ✓ marker", lines[1].contains("✓ commented out stale manifest entry"))
    }

    @Test fun `autoApplyNotificationLines prefixes commit drift and wiki suggestion branches with icons`() {
        val banner = newBanner()
        val lines = banner.autoApplyNotificationLines(listOf(commitDrift(), wikiSuggestion()))
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("↻ "))
        assertTrue(lines[1].startsWith("✍ "))
    }

    @Test fun `autoApplyNotificationLines returns empty for empty input`() {
        val banner = newBanner()
        assertTrue(banner.autoApplyNotificationLines(emptyList()).isEmpty())
    }
}
