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
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WikiLocatorTest {

    @Test fun `default mode resolves to claudeDir wikiSubdir`() {
        val tmp = Files.createTempDirectory("wiki-locator")
        try {
            val resolved = WikiLocator.resolve(
                projectBase = tmp,
                claudeDirName = ".claude",
                wikiSubdir = "wiki",
                configReader = { null },
            )
            assertEquals(tmp.resolve(".claude").resolve("wiki"), resolved.wikiDir)
            assertFalse(resolved.teamMode)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `default mode honors custom claudeDir and wikiSubdir`() {
        val tmp = Files.createTempDirectory("wiki-locator")
        try {
            val resolved = WikiLocator.resolve(
                projectBase = tmp,
                claudeDirName = ".myclaude",
                wikiSubdir = "knowledge",
                configReader = { null },
            )
            assertEquals(tmp.resolve(".myclaude").resolve("knowledge"), resolved.wikiDir)
            assertFalse(resolved.teamMode)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
