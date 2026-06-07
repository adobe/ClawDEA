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

    @Test fun `default mode resolves to clawdea wikiSubdir`() {
        val tmp = Files.createTempDirectory("wiki-locator")
        try {
            val resolved = WikiLocator.resolve(
                projectBase = tmp,
                wikiSubdir = "wiki",
                configReader = { null },
            )
            assertEquals(tmp.resolve(".clawdea").resolve("wiki"), resolved.wikiDir)
            assertFalse(resolved.teamMode)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `default mode honors custom wikiSubdir`() {
        val tmp = Files.createTempDirectory("wiki-locator")
        try {
            val resolved = WikiLocator.resolve(
                projectBase = tmp,
                wikiSubdir = "knowledge",
                configReader = { null },
            )
            assertEquals(tmp.resolve(".clawdea").resolve("knowledge"), resolved.wikiDir)
            assertFalse(resolved.teamMode)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `team mode reads wikiPath from clawdea config`() {
        val tmp = Files.createTempDirectory("wiki-locator-team")
        try {
            val configDir = Files.createDirectories(tmp.resolve(".clawdea"))
            Files.writeString(configDir.resolve("config.json"), """{"wikiPath":"docs/llm-wiki"}""")
            val resolved = WikiLocator.resolve(
                projectBase = tmp,
                wikiSubdir = "wiki",
                configReader = { Files.readString(configDir.resolve("config.json")) },
            )
            assertEquals(tmp.resolve("docs").resolve("llm-wiki"), resolved.wikiDir)
            assertEquals(true, resolved.teamMode)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `malformed config falls back to default mode`() {
        val tmp = Files.createTempDirectory("wiki-locator-bad")
        try {
            val resolved = WikiLocator.resolve(
                projectBase = tmp,
                wikiSubdir = "wiki",
                configReader = { "{not json" },
            )
            assertEquals(tmp.resolve(".clawdea").resolve("wiki"), resolved.wikiDir)
            assertEquals(false, resolved.teamMode)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `blank wikiPath in config falls back to default`() {
        val tmp = Files.createTempDirectory("wiki-locator-blank")
        try {
            val resolved = WikiLocator.resolve(
                projectBase = tmp,
                wikiSubdir = "wiki",
                configReader = { """{"wikiPath":""}""" },
            )
            assertEquals(tmp.resolve(".clawdea").resolve("wiki"), resolved.wikiDir)
            assertEquals(false, resolved.teamMode)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `resolveForRepo reads the sibling repo's own team config`() {
        val tmp = Files.createTempDirectory("wiki-locator-sibling-team")
        try {
            val configDir = Files.createDirectories(tmp.resolve(".clawdea"))
            Files.writeString(configDir.resolve("config.json"), """{"wikiPath":"docs/llm-wiki"}""")
            val resolved = WikiLocator.resolveForRepo(tmp, wikiSubdir = "wiki")
            assertEquals(tmp.resolve("docs").resolve("llm-wiki"), resolved.wikiDir)
            assertEquals(true, resolved.teamMode)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `resolveForRepo falls back to default when sibling has no config`() {
        val tmp = Files.createTempDirectory("wiki-locator-sibling-default")
        try {
            val resolved = WikiLocator.resolveForRepo(tmp, wikiSubdir = "wiki")
            assertEquals(tmp.resolve(".clawdea").resolve("wiki"), resolved.wikiDir)
            assertFalse(resolved.teamMode)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
