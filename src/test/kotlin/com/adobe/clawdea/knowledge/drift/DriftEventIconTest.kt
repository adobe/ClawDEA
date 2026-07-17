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

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class DriftEventIconTest {

    private fun codeRename() = DriftEvent.CodeRename(
        wikiPage = Paths.get(".claude/wiki/concepts/foo.md"),
        brokenLink = "src/old/Foo.kt",
        suggestedReplacement = "src/new/Foo.kt",
    )

    private fun manifestStale() = DriftEvent.ManifestStale(
        repoKey = "my-repo",
        groupName = "siblings",
        manifestPath = Paths.get(".clawdea-workspace.md"),
        lineHint = 42,
    )

    private fun commitDrift() = DriftEvent.CommitDrift(
        wikiPage = Paths.get(".claude/wiki/concepts/bar.md"),
        commitShas = listOf("abc1234"),
        touchedPaths = listOf("src/main/kotlin/Bar.kt"),
        firstObservedAt = "2026-05-17T16:30:00Z",
    )

    private fun orphanSubsystem() = DriftEvent.OrphanSubsystem(
        prefix = "Codex",
        classNames = listOf("CodexProcess", "CodexModelProbe", "CodexAppServerProcess"),
        representativePaths = listOf("src/main/kotlin/com/adobe/clawdea/cli/CodexProcess.kt"),
    )

    private fun wikiSuggestion() = DriftEvent.WikiSuggestion(
        kind = SuggestionKind.missingConcept,
        title = "Add concept for X",
        rationale = "Multiple subsystems reference it.",
        targetFiles = listOf(".claude/wiki/concepts/x.md"),
        sourcePage = null,
        recordedAt = "2026-05-17T16:30:00Z",
    )

    @Test fun `iconFor returns link icon for CodeRename`() {
        assertEquals("🔗", DriftEventIcon.iconFor(codeRename()))
    }

    @Test fun `iconFor returns clipboard icon for ManifestStale`() {
        assertEquals("📋", DriftEventIcon.iconFor(manifestStale()))
    }

    @Test fun `iconFor returns refresh icon for CommitDrift`() {
        assertEquals("↻", DriftEventIcon.iconFor(commitDrift()))
    }

    @Test fun `iconFor returns seedling icon for OrphanSubsystem`() {
        assertEquals("🌱", DriftEventIcon.iconFor(orphanSubsystem()))
    }

    @Test fun `iconFor returns pen icon for WikiSuggestion`() {
        assertEquals("✍", DriftEventIcon.iconFor(wikiSuggestion()))
    }

    @Test fun `labelFor returns stale link for CodeRename`() {
        assertEquals("stale link", DriftEventIcon.labelFor(codeRename()))
    }

    @Test fun `labelFor returns stale manifest for ManifestStale`() {
        assertEquals("stale manifest", DriftEventIcon.labelFor(manifestStale()))
    }

    @Test fun `labelFor returns code changed for CommitDrift`() {
        assertEquals("code changed", DriftEventIcon.labelFor(commitDrift()))
    }

    @Test fun `labelFor returns undocumented subsystem for OrphanSubsystem`() {
        assertEquals("undocumented subsystem", DriftEventIcon.labelFor(orphanSubsystem()))
    }

    @Test fun `labelFor returns suggested update for WikiSuggestion`() {
        assertEquals("suggested update", DriftEventIcon.labelFor(wikiSuggestion()))
    }
}
