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
package com.adobe.clawdea.knowledge.drift

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class WikiAuthorDigestBuilderTest {

    @Test fun `digest starts with the @wiki-author mention`() {
        val out = WikiAuthorDigestBuilder.build(
            listOf(
                DriftEvent.CommitDrift(
                    wikiPage = Paths.get(".claude/wiki/concepts/x.md"),
                    commitShas = listOf("abc"),
                    touchedPaths = listOf("src/main/kotlin/Foo.kt"),
                    firstObservedAt = "2026-05-17T16:30:00Z",
                ),
            ),
        )
        assertTrue(out.startsWith("@wiki-author"))
    }

    @Test fun `digest includes one block per CommitDrift`() {
        val out = WikiAuthorDigestBuilder.build(
            listOf(
                DriftEvent.CommitDrift(
                    wikiPage = Paths.get(".claude/wiki/concepts/x.md"),
                    commitShas = listOf("abc", "def"),
                    touchedPaths = listOf("src/main/kotlin/Foo.kt"),
                    firstObservedAt = "2026-05-17T16:30:00Z",
                ),
                DriftEvent.CommitDrift(
                    wikiPage = Paths.get(".claude/wiki/concepts/y.md"),
                    commitShas = listOf("ghi"),
                    touchedPaths = listOf("src/main/kotlin/Bar.kt"),
                    firstObservedAt = "2026-05-17T16:30:00Z",
                ),
            ),
        )
        assertTrue(out.contains("CommitDrift on .claude/wiki/concepts/x.md"))
        assertTrue(out.contains("CommitDrift on .claude/wiki/concepts/y.md"))
        assertTrue(out.contains("commits: abc, def"))
        assertTrue(out.contains("touched paths that this page mentions: src/main/kotlin/Foo.kt"))
    }

    @Test fun `digest renders CodeRename, ManifestStale, WikiSuggestion`() {
        val out = WikiAuthorDigestBuilder.build(
            listOf(
                DriftEvent.CodeRename(
                    wikiPage = Paths.get(".claude/wiki/concepts/edit.md"),
                    brokenLink = "src/old/Path.kt",
                    suggestedReplacement = "src/new/Path.kt",
                ),
                DriftEvent.ManifestStale(
                    repoKey = "core",
                    groupName = "engine",
                    manifestPath = Paths.get(".clawdea-workspace.md"),
                    lineHint = 12,
                ),
                DriftEvent.WikiSuggestion(
                    kind = SuggestionKind.missingConcept,
                    title = "Add WikiAuthor concept page",
                    rationale = "WikiAuthor subagent has no page covering it.",
                    targetFiles = listOf(".claude/wiki/concepts/wiki-author.md", ".claude/wiki/index.md"),
                    sourcePage = ".claude/wiki/concepts/wiki-librarian.md",
                    recordedAt = "2026-05-17T16:30:00Z",
                ),
            ),
        )
        assertTrue(out.contains("CodeRename in .claude/wiki/concepts/edit.md"))
        assertTrue(out.contains("broken link: src/old/Path.kt"))
        assertTrue(out.contains("suggested replacement: src/new/Path.kt"))
        assertTrue(out.contains("ManifestStale"))
        assertTrue(out.contains("repo key: core"))
        assertTrue(out.contains("WikiSuggestion (missingConcept)"))
        assertTrue(out.contains("rationale: WikiAuthor subagent has no page covering it."))
    }

    @Test fun `digest ends with a summarise-after instruction`() {
        val out = WikiAuthorDigestBuilder.build(
            listOf(
                DriftEvent.CodeRename(
                    wikiPage = Paths.get(".claude/wiki/concepts/x.md"),
                    brokenLink = "a",
                    suggestedReplacement = null,
                ),
            ),
        )
        assertTrue(out.contains("summarise"))
    }
}
