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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DriftAutoApplierTest {

    @Test fun `applies CodeRename with suggestion in place`() {
        val tmp = Files.createTempDirectory("auto")
        val page = tmp.resolve("page.md")
        Files.writeString(page, "See [Foo](old/Foo.kt) for details.\nAnother line.")
        val event = DriftEvent.CodeRename(
            wikiPage = page,
            brokenLink = "old/Foo.kt",
            suggestedReplacement = "new/Foo.kt",
        )
        val applied = DriftAutoApplier.apply(listOf(event), today = "2026-05-04")
        assertEquals(1, applied.size)
        assertTrue(Files.readString(page).contains("[Foo](new/Foo.kt)"))
        assertFalse(Files.readString(page).contains("old/Foo.kt"))
    }

    @Test fun `skips CodeRename without suggestion`() {
        val tmp = Files.createTempDirectory("auto")
        val page = tmp.resolve("page.md")
        val originalText = "See [Foo](old/Foo.kt)"
        Files.writeString(page, originalText)
        val event = DriftEvent.CodeRename(page, "old/Foo.kt", suggestedReplacement = null)
        val applied = DriftAutoApplier.apply(listOf(event), today = "2026-05-04")
        assertEquals(emptyList<DriftEvent>(), applied)
        assertEquals(originalText, Files.readString(page))
    }

    @Test fun `comments out ManifestStale bullet with date marker`() {
        val tmp = Files.createTempDirectory("auto")
        val manifest = tmp.resolve(".clawdea-workspace.md")
        val original = """
            # Workspace: ws

            ## Repos: g

            - **alive** `alive-repo` — alive
            - **dead** `missing-repo` — was here once
        """.trimIndent()
        Files.writeString(manifest, original)
        val deadLine = original.lines().indexOfFirst { it.contains("**dead**") } + 1
        val event = DriftEvent.ManifestStale("dead", "g", manifest, deadLine)
        val applied = DriftAutoApplier.apply(listOf(event), today = "2026-05-04")
        assertEquals(1, applied.size)
        val updated = Files.readString(manifest)
        assertTrue("dead bullet should now start with '#'",
            updated.lines()[deadLine - 1].trimStart().startsWith("#"))
        assertTrue("date marker should appear", updated.contains("auto-removed 2026-05-04"))
        assertTrue("alive bullet untouched", updated.contains("- **alive**"))
    }

    @Test fun `applyCodeRename returns false when broken link is bare prose without parens`() {
        val tmp = Files.createTempDirectory("auto")
        val page = tmp.resolve("page.md")
        val original = "Earlier we used `old/Foo.kt` for this." // bare backtick prose; no markdown-link form
        Files.writeString(page, original)
        val event = DriftEvent.CodeRename(page, "old/Foo.kt", "new/Foo.kt")
        val applied = DriftAutoApplier.apply(listOf(event), today = "2026-05-04")
        assertEquals(emptyList<DriftEvent>(), applied)
        assertEquals(original, Files.readString(page))
    }

    @Test fun `is idempotent on already-applied events`() {
        val tmp = Files.createTempDirectory("auto")
        val page = tmp.resolve("page.md")
        Files.writeString(page, "See [Foo](old/Foo.kt)")
        val event = DriftEvent.CodeRename(page, "old/Foo.kt", "new/Foo.kt")
        DriftAutoApplier.apply(listOf(event), today = "2026-05-04")
        val firstResult = Files.readString(page)
        // Second apply with the same event: brokenLink is no longer in the file → no change.
        val secondApplied = DriftAutoApplier.apply(listOf(event), today = "2026-05-04")
        assertEquals(emptyList<DriftEvent>(), secondApplied)
        assertEquals(firstResult, Files.readString(page))
    }
}
