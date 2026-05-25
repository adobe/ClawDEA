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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Unit tests for the pure-logic helpers on [WikiGitStateChecker]. The Project-aware
 * [check]/[fix] paths require git4idea and are exercised by the IDE-bound tests.
 */
class WikiGitStateCheckerTest {

    @Test
    fun `removeGitignoreLine drops the matching line and leaves others intact`() {
        val tmp = Files.createTempDirectory("wgs-test")
        try {
            val gitignore = tmp.resolve(".gitignore")
            Files.writeString(gitignore, "build/\n.clawdea/wiki-state.local.json\n*.log\n")

            WikiGitStateChecker.removeGitignoreLine(tmp, ".clawdea/wiki-state.local.json")

            val after = Files.readString(gitignore)
            assertFalse(after.contains(".clawdea/wiki-state.local.json"))
            assertTrue(after.contains("build/"))
            assertTrue(after.contains("*.log"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `removeGitignoreLine is a no-op when entry is absent`() {
        val tmp = Files.createTempDirectory("wgs-test")
        try {
            val gitignore = tmp.resolve(".gitignore")
            val before = "build/\n*.log\n"
            Files.writeString(gitignore, before)

            WikiGitStateChecker.removeGitignoreLine(tmp, ".clawdea/wiki-state.local.json")

            assertEquals(before, Files.readString(gitignore))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `removeGitignoreLine is a no-op when gitignore is missing`() {
        val tmp = Files.createTempDirectory("wgs-test")
        try {
            // Should not throw, and should not create the file.
            WikiGitStateChecker.removeGitignoreLine(tmp, "anything")
            assertFalse(Files.exists(tmp.resolve(".gitignore")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `removeGitignoreLine handles trailing-whitespace lines`() {
        val tmp = Files.createTempDirectory("wgs-test")
        try {
            val gitignore = tmp.resolve(".gitignore")
            Files.writeString(gitignore, "build/\n  .clawdea/wiki-state.local.json  \n*.log\n")

            WikiGitStateChecker.removeGitignoreLine(tmp, ".clawdea/wiki-state.local.json")

            val after = Files.readString(gitignore)
            assertFalse(after.contains("wiki-state.local.json"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Issue summary describes tracked-but-should-be-ignored case`() {
        val issue = WikiGitStateChecker.Issue(
            relativePath = ".clawdea/wiki-state.local.json",
            expected = WikiGitStateChecker.Expectation.IGNORED,
            actuallyTracked = true,
            actuallyIgnored = false,
            fileExists = true,
        )
        assertTrue(issue.summary().contains("tracked"))
        assertTrue(issue.summary().contains("gitignored"))
    }

    @Test
    fun `Issue summary describes ignored-but-should-be-tracked case`() {
        val issue = WikiGitStateChecker.Issue(
            relativePath = ".clawdea/config.json",
            expected = WikiGitStateChecker.Expectation.TRACKED,
            actuallyTracked = false,
            actuallyIgnored = true,
            fileExists = true,
        )
        assertTrue(issue.summary().contains("gitignored"))
        assertTrue(issue.summary().contains("committed"))
    }

    @Test
    fun `Issue summary describes missing-file case for tracked expectation`() {
        val issue = WikiGitStateChecker.Issue(
            relativePath = "docs/llm-wiki/.wiki-state.json",
            expected = WikiGitStateChecker.Expectation.TRACKED,
            actuallyTracked = false,
            actuallyIgnored = false,
            fileExists = false,
        )
        assertTrue(issue.summary().contains("missing"))
    }
}
