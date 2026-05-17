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

class CommitWikiDriftDetectorTest {

    @Test fun `intersect emits CommitDrift for pages whose mentions match touched paths`() {
        val tmp = Files.createTempDirectory("commit-drift-test")
        try {
            val wiki = tmp.resolve(".claude/wiki/concepts")
            Files.createDirectories(wiki)
            val pageA = wiki.resolve("page-a.md")
            val pageB = wiki.resolve("page-b.md")
            Files.writeString(pageA, """
                # Page A
                Mentions WikiAgentsArg and src/main/kotlin/com/adobe/Foo.kt.
            """.trimIndent())
            Files.writeString(pageB, """
                # Page B
                Mentions DriftEvent and nothing else relevant.
            """.trimIndent())

            val commits = listOf(
                CommitWikiDriftDetector.CommitInfo(
                    sha = "abc1234",
                    touchedPaths = listOf("src/main/kotlin/com/adobe/Foo.kt"),
                ),
                CommitWikiDriftDetector.CommitInfo(
                    sha = "def5678",
                    touchedPaths = listOf("docs/random.md"),
                ),
            )

            val events = CommitWikiDriftDetector.intersect(
                wikiDir = tmp.resolve(".claude/wiki"),
                commits = commits,
                now = java.time.Instant.parse("2026-05-17T16:30:00Z"),
            )

            assertEquals(1, events.size)
            val event = events.single()
            assertEquals("page-a.md", event.wikiPage.fileName.toString())
            assertEquals(listOf("abc1234"), event.commitShas)
            assertEquals(listOf("src/main/kotlin/com/adobe/Foo.kt"), event.touchedPaths)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `intersect emits nothing when no page mentions any touched path`() {
        val tmp = Files.createTempDirectory("commit-drift-test")
        try {
            val wiki = tmp.resolve(".claude/wiki/concepts")
            Files.createDirectories(wiki)
            Files.writeString(wiki.resolve("page.md"), "# Page\nNo mentions here.")

            val commits = listOf(
                CommitWikiDriftDetector.CommitInfo(
                    sha = "abc1234",
                    touchedPaths = listOf("src/main/kotlin/Untouched.kt"),
                ),
            )

            val events = CommitWikiDriftDetector.intersect(
                wikiDir = tmp.resolve(".claude/wiki"),
                commits = commits,
                now = java.time.Instant.parse("2026-05-17T16:30:00Z"),
            )

            assertTrue(events.isEmpty())
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `intersect groups multiple commits per page`() {
        val tmp = Files.createTempDirectory("commit-drift-test")
        try {
            val wiki = tmp.resolve(".claude/wiki/concepts")
            Files.createDirectories(wiki)
            Files.writeString(wiki.resolve("page.md"), "# Page\nMentions WikiAgentsArg.")

            val commits = listOf(
                CommitWikiDriftDetector.CommitInfo(
                    sha = "abc",
                    touchedPaths = listOf("WikiAgentsArg"),
                ),
                CommitWikiDriftDetector.CommitInfo(
                    sha = "def",
                    touchedPaths = listOf("WikiAgentsArg"),
                ),
            )

            val events = CommitWikiDriftDetector.intersect(
                wikiDir = tmp.resolve(".claude/wiki"),
                commits = commits,
                now = java.time.Instant.parse("2026-05-17T16:30:00Z"),
            )

            assertEquals(1, events.size)
            assertEquals(listOf("abc", "def"), events.single().commitShas)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun `intersect handles empty wiki dir gracefully`() {
        val tmp = Files.createTempDirectory("commit-drift-test")
        try {
            val events = CommitWikiDriftDetector.intersect(
                wikiDir = tmp.resolve(".claude/wiki"),  // doesn't exist
                commits = listOf(CommitWikiDriftDetector.CommitInfo("abc", listOf("src/main/kotlin/X.kt"))),
                now = java.time.Instant.parse("2026-05-17T16:30:00Z"),
            )
            assertTrue(events.isEmpty())
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
