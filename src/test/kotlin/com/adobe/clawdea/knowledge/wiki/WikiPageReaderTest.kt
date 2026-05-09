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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WikiPageReaderTest {
    private lateinit var tmp: Path
    private lateinit var wiki: WikiPath
    private lateinit var reader: WikiPageReader

    @Before fun setup() {
        tmp = Files.createTempDirectory("wiki-reader-")
        wiki = WikiPath(rootDir = tmp)
        reader = WikiPageReader(wiki)
        Files.createDirectories(tmp.resolve("concepts"))
        Files.createDirectories(tmp.resolve("sources"))
    }

    @After fun teardown() { tmp.toFile().deleteRecursively() }

    @Test fun `reads concept page when present`() {
        Files.writeString(tmp.resolve("concepts/rollout-flow.md"), "# Rollout flow\n\nBody.\n")
        assertEquals("# Rollout flow\n\nBody.", reader.readConcept("rollout-flow"))
    }

    @Test fun `returns null when concept missing`() {
        assertNull(reader.readConcept("does-not-exist"))
    }

    @Test fun `returns null on path traversal`() {
        assertNull(reader.readConcept("../../etc/passwd"))
    }

    @Test fun `reads source page`() {
        Files.writeString(tmp.resolve("sources/runbook.md"), "Runbook content")
        assertEquals("Runbook content", reader.readSource("runbook"))
    }

    @Test fun `reads index page`() {
        Files.writeString(tmp.resolve("index.md"), "# Index")
        assertEquals("# Index", reader.readIndex())
    }
}
