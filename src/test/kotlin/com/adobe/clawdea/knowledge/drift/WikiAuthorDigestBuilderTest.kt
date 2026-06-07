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

    @Test fun `WikiSuggestion paths are rewritten to the actual team-mode wiki dir`() {
        val wikiDir = Paths.get("/repo/docs/llm-wiki")
        val out = WikiAuthorDigestBuilder.build(
            listOf(
                DriftEvent.WikiSuggestion(
                    kind = SuggestionKind.missingConcept,
                    title = "Add component authoring layout page",
                    rationale = "No page covers the authoring side of a new component.",
                    targetFiles = listOf(
                        ".claude/wiki/concepts/component-authoring-layout.md",
                        ".claude/wiki/index.md",
                    ),
                    sourcePage = ".claude/wiki/concepts/htl-components-and-amp.md",
                    recordedAt = "2026-06-07T18:56:05Z",
                ),
            ),
            wikiDir = wikiDir,
        )
        assertTrue(
            "expected target files resolved under the team wiki dir, got:\n$out",
            out.contains("target files: /repo/docs/llm-wiki/concepts/component-authoring-layout.md, /repo/docs/llm-wiki/index.md"),
        )
        assertTrue(
            "expected source page resolved under the team wiki dir, got:\n$out",
            out.contains("observed while reading: /repo/docs/llm-wiki/concepts/htl-components-and-amp.md"),
        )
        assertTrue(
            "logical .claude/wiki prefix should not leak into the digest, got:\n$out",
            !out.contains(".claude/wiki/"),
        )
    }

    @Test fun `WikiSuggestion paths are left as-is when no wiki dir is given`() {
        val out = WikiAuthorDigestBuilder.build(
            listOf(
                DriftEvent.WikiSuggestion(
                    kind = SuggestionKind.missingConcept,
                    title = "Add concept page",
                    rationale = "A real subsystem has no page covering it yet.",
                    targetFiles = listOf(".claude/wiki/concepts/foo.md"),
                    sourcePage = null,
                    recordedAt = "2026-06-07T18:56:05Z",
                ),
            ),
        )
        assertTrue(out.contains("target files: .claude/wiki/concepts/foo.md"))
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

    @Test fun `digest prefixes CommitDrift line with refresh icon`() {
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
        assertTrue(
            "expected '- ↻ CommitDrift' prefix in digest, got:\n$out",
            out.contains("- ↻ CommitDrift on .claude/wiki/concepts/x.md"),
        )
    }

    @Test fun `digest prefixes CodeRename line with link icon`() {
        val out = WikiAuthorDigestBuilder.build(
            listOf(
                DriftEvent.CodeRename(
                    wikiPage = Paths.get(".claude/wiki/concepts/edit.md"),
                    brokenLink = "src/old/Path.kt",
                    suggestedReplacement = "src/new/Path.kt",
                ),
            ),
        )
        assertTrue(
            "expected '- 🔗 CodeRename' prefix in digest, got:\n$out",
            out.contains("- 🔗 CodeRename in .claude/wiki/concepts/edit.md"),
        )
    }

    @Test fun `digest prefixes ManifestStale line with clipboard icon`() {
        val out = WikiAuthorDigestBuilder.build(
            listOf(
                DriftEvent.ManifestStale(
                    repoKey = "core",
                    groupName = "engine",
                    manifestPath = Paths.get(".clawdea-workspace.md"),
                    lineHint = 12,
                ),
            ),
        )
        assertTrue(
            "expected '- 📋 ManifestStale' prefix in digest, got:\n$out",
            out.contains("- 📋 ManifestStale in .clawdea-workspace.md"),
        )
    }

    @Test fun `digest prefixes WikiSuggestion line with pen icon`() {
        val out = WikiAuthorDigestBuilder.build(
            listOf(
                DriftEvent.WikiSuggestion(
                    kind = SuggestionKind.missingConcept,
                    title = "Add WikiAuthor concept page",
                    rationale = "WikiAuthor subagent has no page covering it.",
                    targetFiles = listOf(".claude/wiki/concepts/wiki-author.md"),
                    sourcePage = null,
                    recordedAt = "2026-05-17T16:30:00Z",
                ),
            ),
        )
        assertTrue(
            "expected '- ✍ WikiSuggestion' prefix in digest, got:\n$out",
            out.contains("- ✍ WikiSuggestion (missingConcept): Add WikiAuthor concept page"),
        )
    }
}
