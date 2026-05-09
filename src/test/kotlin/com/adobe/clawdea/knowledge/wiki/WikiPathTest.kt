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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Paths

class WikiPathTest {
    private val root = Paths.get("/proj")
    private val wiki = WikiPath(rootDir = root.resolve(".claude/wiki"))

    @Test
    fun `concept resolves to concepts subdir`() {
        assertEquals(root.resolve(".claude/wiki/concepts/rollout-flow.md"), wiki.concept("rollout-flow"))
    }

    @Test
    fun `source resolves to sources subdir`() {
        assertEquals(root.resolve(".claude/wiki/sources/runbook.md"), wiki.source("runbook"))
    }

    @Test
    fun `index resolves to root`() {
        assertEquals(root.resolve(".claude/wiki/index.md"), wiki.index())
    }

    @Test
    fun `concept rejects path traversal`() {
        assertNull(wiki.concept("../../../etc/passwd"))
        assertNull(wiki.concept("foo/bar"))
        assertNull(wiki.concept("foo\\bar"))
        assertNull(wiki.concept(""))
    }

    @Test
    fun `concept normalizes md suffix`() {
        assertEquals(root.resolve(".claude/wiki/concepts/foo.md"), wiki.concept("foo"))
        assertEquals(root.resolve(".claude/wiki/concepts/foo.md"), wiki.concept("foo.md"))
    }
}
