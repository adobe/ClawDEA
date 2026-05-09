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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CodeRenameDetectorTest {

    private fun mkWiki(root: Path, page: String, content: String): Path {
        val file = root.resolve("wiki").resolve("concepts").resolve(page)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return file
    }

    private fun mkSrc(root: Path, rel: String): Path {
        val file = root.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, "package x\n")
        return file
    }

    @Test fun `broken link with single basename match suggests replacement`() {
        val tmp = Files.createTempDirectory("crd")
        mkWiki(tmp, "page.md", "See [Foo](../../src/main/kotlin/old/path/Foo.kt) for details")
        mkSrc(tmp, "src/main/kotlin/new/path/Foo.kt")
        val events = CodeRenameDetector.detect(
            wikiDir = tmp.resolve("wiki"),
            sourceRoots = listOf(tmp.resolve("src/main/kotlin")),
        )
        assertEquals(1, events.size)
        val ev = events.single() as DriftEvent.CodeRename
        assertTrue("brokenLink contains old path", ev.brokenLink.contains("old/path/Foo.kt"))
        assertTrue("suggestion contains new path",
            ev.suggestedReplacement?.contains("new/path/Foo.kt") ?: false)
    }

    @Test fun `broken link with multiple basename matches has no suggestion`() {
        val tmp = Files.createTempDirectory("crd")
        mkWiki(tmp, "p.md", "[Foo](../../src/main/kotlin/x/Foo.kt)")
        mkSrc(tmp, "src/main/kotlin/a/Foo.kt")
        mkSrc(tmp, "src/main/kotlin/b/Foo.kt")
        val events = CodeRenameDetector.detect(tmp.resolve("wiki"), listOf(tmp.resolve("src/main/kotlin")))
        assertEquals(1, events.size)
        assertEquals(null, (events.single() as DriftEvent.CodeRename).suggestedReplacement)
    }

    @Test fun `broken link with zero basename matches has no suggestion`() {
        val tmp = Files.createTempDirectory("crd")
        mkWiki(tmp, "p.md", "[Foo](../../src/main/kotlin/x/Foo.kt)")
        Files.createDirectories(tmp.resolve("src/main/kotlin"))
        val events = CodeRenameDetector.detect(tmp.resolve("wiki"), listOf(tmp.resolve("src/main/kotlin")))
        assertEquals(1, events.size)
        assertEquals(null, (events.single() as DriftEvent.CodeRename).suggestedReplacement)
    }

    @Test fun `existing link produces no event`() {
        val tmp = Files.createTempDirectory("crd")
        mkWiki(tmp, "p.md", "[Foo](../../src/main/kotlin/x/Foo.kt)")
        mkSrc(tmp, "src/main/kotlin/x/Foo.kt")
        val events = CodeRenameDetector.detect(tmp.resolve("wiki"), listOf(tmp.resolve("src/main/kotlin")))
        assertEquals(emptyList<DriftEvent>(), events)
    }

    @Test fun `anchor-only link is skipped`() {
        val tmp = Files.createTempDirectory("crd")
        mkWiki(tmp, "p.md", "[Section](#some-section)")
        val events = CodeRenameDetector.detect(tmp.resolve("wiki"), emptyList())
        assertEquals(emptyList<DriftEvent>(), events)
    }

    @Test fun `schemed URL link is skipped`() {
        val tmp = Files.createTempDirectory("crd")
        mkWiki(tmp, "p.md", "[ref](https://example.com/x)")
        val events = CodeRenameDetector.detect(tmp.resolve("wiki"), emptyList())
        assertEquals(emptyList<DriftEvent>(), events)
    }

    @Test fun `wikilink syntax is skipped`() {
        val tmp = Files.createTempDirectory("crd")
        mkWiki(tmp, "p.md", "See [[other-concept]] for context")
        val events = CodeRenameDetector.detect(tmp.resolve("wiki"), emptyList())
        assertEquals(emptyList<DriftEvent>(), events)
    }

    @Test fun `link with line suffix strips suffix for VFS resolution`() {
        val tmp = Files.createTempDirectory("crd")
        mkWiki(tmp, "p.md", "[Foo](../../src/main/kotlin/x/Foo.kt:42-50)")
        mkSrc(tmp, "src/main/kotlin/x/Foo.kt")  // file exists; line suffix should not break check
        val events = CodeRenameDetector.detect(tmp.resolve("wiki"), listOf(tmp.resolve("src/main/kotlin")))
        assertEquals(emptyList<DriftEvent>(), events)
    }
}
