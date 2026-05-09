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
package com.adobe.clawdea.knowledge.notes

import org.junit.Assert.assertTrue
import org.junit.Test

class PromoteToWikiPromptBuilderTest {
    @Test fun `prompt embeds the source note content verbatim`() {
        val out = PromoteToWikiPromptBuilder.build(
            sourceAbsolutePath = "/p/.claude/projects/-p/notes/CURRENT.md",
            wikiRelativePath = ".claude/wiki",
            sourceContent = "MARKER_FOR_NOTE_BODY",
        )
        assertTrue(out.contains("MARKER_FOR_NOTE_BODY"))
    }

    @Test fun `prompt instructs use of propose_edit and propose_write`() {
        val out = PromoteToWikiPromptBuilder.build(
            sourceAbsolutePath = "/abs/note.md",
            wikiRelativePath = ".claude/wiki",
            sourceContent = "x",
        )
        assertTrue(out.contains("propose_edit"))
        assertTrue(out.contains("propose_write"))
    }

    @Test fun `prompt includes the source-preservation instruction with the absolute path`() {
        val out = PromoteToWikiPromptBuilder.build(
            sourceAbsolutePath = "/abs/note.md",
            wikiRelativePath = ".claude/wiki",
            sourceContent = "x",
        )
        assertTrue(out.contains("Do NOT delete or modify"))
        assertTrue(out.contains("/abs/note.md"))
    }

    @Test fun `prompt references the wiki index and concept location`() {
        val out = PromoteToWikiPromptBuilder.build(
            sourceAbsolutePath = "/abs/note.md",
            wikiRelativePath = ".claude/wiki",
            sourceContent = "x",
        )
        assertTrue(out.contains(".claude/wiki/index.md"))
        assertTrue(out.contains("concepts"))
    }

    @Test fun `prompt forbids built-in Write and Edit even with auto-accept enabled`() {
        val out = PromoteToWikiPromptBuilder.build(
            sourceAbsolutePath = "/abs/note.md",
            wikiRelativePath = ".claude/wiki",
            sourceContent = "x",
        )
        val lower = out.lowercase()
        assertTrue("expected explicit guidance against built-in Write/Edit",
            lower.contains("auto-accept") || lower.contains("auto accept"))
        assertTrue(lower.contains("publish event"))
    }

    @Test fun `prompt mentions the format guide for concept pages`() {
        val out = PromoteToWikiPromptBuilder.build(
            sourceAbsolutePath = "/abs/note.md",
            wikiRelativePath = ".claude/wiki",
            sourceContent = "x",
        )
        assertTrue(out.contains("150"))
        assertTrue(out.lowercase().contains("concept page"))
    }
}
