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

import com.adobe.clawdea.knowledge.drift.DriftState
import com.adobe.clawdea.knowledge.drift.DriftStateStore
import com.adobe.clawdea.knowledge.drift.SuggestionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class WikiSuggestionWriterTest {

    private fun newClaudeDir() = Files.createTempDirectory("clawdea-wiki-sugg")

    @Test fun `records new suggestion and persists to drift-state`() {
        val claudeDir = newClaudeDir()
        val writer = WikiSuggestionWriter(claudeDir)
        val result = writer.record(
            kind = "missingConcept",
            title = "Add FilesystemRefreshCoordinator page",
            rationale = "Referenced from multiple subsystems with no coverage.",
            targetFilesCsv = ".claude/wiki/concepts/fsrc.md, .claude/wiki/index.md",
            sourcePage = null,
            recordedAt = Instant.parse("2026-05-16T16:30:00Z"),
        )
        assertTrue(result is WikiSuggestionWriter.Result.Recorded)
        val recorded = result as WikiSuggestionWriter.Result.Recorded
        assertTrue(recorded.isNew)
        assertEquals("wiki-suggestion:missingConcept:.claude/wiki/concepts/fsrc.md", recorded.signature)

        val state = DriftStateStore.read(claudeDir)
        assertEquals(1, state.suggestions.size)
        assertEquals(
            listOf(".claude/wiki/concepts/fsrc.md", ".claude/wiki/index.md"),
            state.suggestions[0].targetFiles,
        )
    }

    @Test fun `re-recording same signature updates in place`() {
        val claudeDir = newClaudeDir()
        val writer = WikiSuggestionWriter(claudeDir)
        writer.record("missingConcept", "Old title here", "Old rationale of the gap.",
            ".claude/wiki/concepts/x.md", null, Instant.parse("2026-05-16T10:00:00Z"))
        val second = writer.record("missingConcept", "New title here", "Updated rationale of the gap.",
            ".claude/wiki/concepts/x.md", null, Instant.parse("2026-05-16T18:00:00Z"))
        assertTrue(second is WikiSuggestionWriter.Result.Recorded)
        assertEquals(false, (second as WikiSuggestionWriter.Result.Recorded).isNew)

        val state = DriftStateStore.read(claudeDir)
        assertEquals(1, state.suggestions.size)
        assertEquals("New title here", state.suggestions[0].title)
        assertEquals("2026-05-16T18:00:00Z", state.suggestions[0].recordedAt)
    }

    @Test fun `dismissed signature is not re-persisted`() {
        val claudeDir = newClaudeDir()
        DriftStateStore.write(claudeDir, DriftState(
            dismissed = listOf("wiki-suggestion:staleConcept:.claude/wiki/concepts/y.md"),
        ))
        val writer = WikiSuggestionWriter(claudeDir)
        val result = writer.record("staleConcept", "Concept Y is wrong",
            "Wiki says X but source has Y now.",
            ".claude/wiki/concepts/y.md", ".claude/wiki/concepts/y.md")
        assertTrue(result is WikiSuggestionWriter.Result.Dismissed)
        val state = DriftStateStore.read(claudeDir)
        assertEquals(0, state.suggestions.size)
    }

    @Test fun `invalid kind is rejected`() {
        val writer = WikiSuggestionWriter(newClaudeDir())
        val result = writer.record("notARealKind", "Some title here",
            "Some rationale of the gap.", ".claude/wiki/concepts/a.md", null)
        assertTrue(result is WikiSuggestionWriter.Result.Invalid)
    }

    @Test fun `title too short is rejected`() {
        val writer = WikiSuggestionWriter(newClaudeDir())
        val result = writer.record("missingConcept", "ab",
            "Some rationale of the gap.", ".claude/wiki/concepts/a.md", null)
        assertTrue(result is WikiSuggestionWriter.Result.Invalid)
    }

    @Test fun `rationale too short is rejected`() {
        val writer = WikiSuggestionWriter(newClaudeDir())
        val result = writer.record("missingConcept", "Valid title here",
            "short", ".claude/wiki/concepts/a.md", null)
        assertTrue(result is WikiSuggestionWriter.Result.Invalid)
    }

    @Test fun `target path outside wiki is rejected`() {
        val writer = WikiSuggestionWriter(newClaudeDir())
        val result = writer.record("missingConcept", "Valid title here",
            "Some rationale of the gap.", "src/main/kotlin/Foo.kt", null)
        assertTrue(result is WikiSuggestionWriter.Result.Invalid)
    }

    @Test fun `target path with parent traversal is rejected`() {
        val writer = WikiSuggestionWriter(newClaudeDir())
        val result = writer.record("missingConcept", "Valid title here",
            "Some rationale of the gap.",
            ".claude/wiki/../../../etc/passwd.md", null)
        assertTrue(result is WikiSuggestionWriter.Result.Invalid)
    }

    @Test fun `non-md target path is rejected`() {
        val writer = WikiSuggestionWriter(newClaudeDir())
        val result = writer.record("missingConcept", "Valid title here",
            "Some rationale of the gap.", ".claude/wiki/concepts/foo.txt", null)
        assertTrue(result is WikiSuggestionWriter.Result.Invalid)
    }

    @Test fun `bare slug is normalized to concepts path with md suffix`() {
        val claudeDir = newClaudeDir()
        val writer = WikiSuggestionWriter(claudeDir)
        val result = writer.record("missingConcept", "Add cli-bridge concept",
            "Page is missing for the CLI bridge subsystem.", "cli-bridge", null)
        assertTrue(result is WikiSuggestionWriter.Result.Recorded)
        val state = DriftStateStore.read(claudeDir)
        assertEquals(listOf(".claude/wiki/concepts/cli-bridge.md"), state.suggestions[0].targetFiles)
    }

    @Test fun `concepts-prefixed path is normalized with claude wiki prefix`() {
        val claudeDir = newClaudeDir()
        val writer = WikiSuggestionWriter(claudeDir)
        val result = writer.record("missingConcept", "Add foo concept",
            "Page foo is missing from concepts.", "concepts/foo.md", null)
        assertTrue(result is WikiSuggestionWriter.Result.Recorded)
        val state = DriftStateStore.read(claudeDir)
        assertEquals(listOf(".claude/wiki/concepts/foo.md"), state.suggestions[0].targetFiles)
    }

    @Test fun `index slug normalizes to wiki index`() {
        val claudeDir = newClaudeDir()
        val writer = WikiSuggestionWriter(claudeDir)
        val result = writer.record("incompleteConcept", "Index needs entry",
            "TOC missing the new concept page.", "index", null)
        assertTrue(result is WikiSuggestionWriter.Result.Recorded)
        val state = DriftStateStore.read(claudeDir)
        assertEquals(listOf(".claude/wiki/index.md"), state.suggestions[0].targetFiles)
    }
}
