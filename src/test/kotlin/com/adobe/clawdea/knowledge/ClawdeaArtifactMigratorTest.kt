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
package com.adobe.clawdea.knowledge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ClawdeaArtifactMigratorTest {

    private lateinit var tmp: Path

    @Before fun setup() { tmp = Files.createTempDirectory("clawdea-migrator-") }
    @After fun teardown() { tmp.toFile().deleteRecursively() }

    @Test
    fun `moves legacy REPO_STATE from claude to clawdea`() {
        val claude = Files.createDirectories(tmp.resolve(".claude"))
        Files.writeString(claude.resolve("REPO_STATE.md"), "# state\n")

        val moved = ClawdeaArtifactMigrator.migrate(tmp, claudeDirName = ".claude")

        assertEquals(1, moved)
        assertFalse(Files.exists(claude.resolve("REPO_STATE.md")))
        assertEquals("# state\n", Files.readString(tmp.resolve(".clawdea/REPO_STATE.md")))
    }

    @Test
    fun `migrate no longer moves SIBLINGS_md`() {
        val claude = Files.createDirectories(tmp.resolve(".claude"))
        Files.writeString(claude.resolve("SIBLINGS.md"), "# sib\n")

        val moved = ClawdeaArtifactMigrator.migrate(tmp, claudeDirName = ".claude")

        assertEquals(0, moved)
        // migrate() ignores SIBLINGS.md entirely; removeStaleSiblings handles it.
        assertTrue(Files.exists(claude.resolve("SIBLINGS.md")))
    }

    // --- removeStaleSiblings ---

    @Test
    fun `removeStaleSiblings deletes copies in claude and clawdea`() {
        val claude = Files.createDirectories(tmp.resolve(".claude"))
        val clawdea = Files.createDirectories(tmp.resolve(".clawdea"))
        Files.writeString(claude.resolve("SIBLINGS.md"), "# legacy\n")
        Files.writeString(clawdea.resolve("SIBLINGS.md"), "# new\n")

        val removed = ClawdeaArtifactMigrator.removeStaleSiblings(tmp, claudeDirName = ".claude")

        assertEquals(2, removed)
        assertFalse(Files.exists(claude.resolve("SIBLINGS.md")))
        assertFalse(Files.exists(clawdea.resolve("SIBLINGS.md")))
    }

    @Test
    fun `removeStaleSiblings is a no-op when none exist`() {
        assertEquals(0, ClawdeaArtifactMigrator.removeStaleSiblings(tmp, claudeDirName = ".claude"))
    }

    @Test
    fun `is a no-op when nothing to migrate`() {
        val moved = ClawdeaArtifactMigrator.migrate(tmp, claudeDirName = ".claude")
        assertEquals(0, moved)
        assertFalse(Files.exists(tmp.resolve(".clawdea")))
    }

    @Test
    fun `drops stale legacy file when target already exists`() {
        val claude = Files.createDirectories(tmp.resolve(".claude"))
        Files.writeString(claude.resolve("REPO_STATE.md"), "# old\n")
        val clawdea = Files.createDirectories(tmp.resolve(".clawdea"))
        Files.writeString(clawdea.resolve("REPO_STATE.md"), "# current\n")

        val moved = ClawdeaArtifactMigrator.migrate(tmp, claudeDirName = ".claude")

        assertEquals(0, moved)
        assertFalse("stale legacy copy should be removed", Files.exists(claude.resolve("REPO_STATE.md")))
        assertEquals("# current\n", Files.readString(clawdea.resolve("REPO_STATE.md")))
    }

    @Test
    fun `honors a custom claude dir name`() {
        val legacy = Files.createDirectories(tmp.resolve(".myclaude"))
        Files.writeString(legacy.resolve("REPO_STATE.md"), "# state\n")

        val moved = ClawdeaArtifactMigrator.migrate(tmp, claudeDirName = ".myclaude")

        assertEquals(1, moved)
        assertTrue(Files.exists(tmp.resolve(".clawdea/REPO_STATE.md")))
        assertFalse(Files.exists(legacy.resolve("REPO_STATE.md")))
    }

    @Test
    fun `running twice is idempotent`() {
        val claude = Files.createDirectories(tmp.resolve(".claude"))
        Files.writeString(claude.resolve("REPO_STATE.md"), "# state\n")

        assertEquals(1, ClawdeaArtifactMigrator.migrate(tmp, claudeDirName = ".claude"))
        assertEquals(0, ClawdeaArtifactMigrator.migrate(tmp, claudeDirName = ".claude"))
        assertEquals("# state\n", Files.readString(tmp.resolve(".clawdea/REPO_STATE.md")))
    }

    // --- migrateWikiDir ---

    @Test
    fun `migrateWikiDir moves the default-mode wiki tree`() {
        val wiki = Files.createDirectories(tmp.resolve(".claude/wiki/concepts"))
        Files.writeString(tmp.resolve(".claude/wiki/index.md"), "# idx\n")
        Files.writeString(wiki.resolve("a.md"), "a\n")

        val moved = ClawdeaArtifactMigrator.migrateWikiDir(tmp, claudeDirName = ".claude", wikiSubdir = "wiki")

        assertTrue(moved)
        assertFalse(Files.exists(tmp.resolve(".claude/wiki")))
        assertEquals("# idx\n", Files.readString(tmp.resolve(".clawdea/wiki/index.md")))
        assertEquals("a\n", Files.readString(tmp.resolve(".clawdea/wiki/concepts/a.md")))
    }

    @Test
    fun `migrateWikiDir is a no-op in team mode`() {
        Files.createDirectories(tmp.resolve(".clawdea"))
        Files.writeString(tmp.resolve(".clawdea/config.json"), """{"wikiPath":"docs/llm-wiki"}""")
        Files.createDirectories(tmp.resolve(".claude/wiki"))
        Files.writeString(tmp.resolve(".claude/wiki/index.md"), "# idx\n")

        val moved = ClawdeaArtifactMigrator.migrateWikiDir(tmp, claudeDirName = ".claude", wikiSubdir = "wiki")

        assertFalse(moved)
        // Legacy wiki untouched — team mode keeps its configured path; the
        // legacy tree is the relocation handler's concern, not the migrator's.
        assertTrue(Files.exists(tmp.resolve(".claude/wiki/index.md")))
    }

    @Test
    fun `migrateWikiDir does not clobber an existing clawdea wiki`() {
        Files.createDirectories(tmp.resolve(".claude/wiki"))
        Files.writeString(tmp.resolve(".claude/wiki/index.md"), "# legacy\n")
        Files.createDirectories(tmp.resolve(".clawdea/wiki"))
        Files.writeString(tmp.resolve(".clawdea/wiki/index.md"), "# current\n")

        val moved = ClawdeaArtifactMigrator.migrateWikiDir(tmp, claudeDirName = ".claude", wikiSubdir = "wiki")

        assertFalse(moved)
        assertEquals("# current\n", Files.readString(tmp.resolve(".clawdea/wiki/index.md")))
        // Legacy still has content, so it's left for the user (not silently dropped).
        assertTrue(Files.exists(tmp.resolve(".claude/wiki/index.md")))
    }

    @Test
    fun `migrateWikiDir is a no-op with no legacy wiki`() {
        assertFalse(ClawdeaArtifactMigrator.migrateWikiDir(tmp, claudeDirName = ".claude", wikiSubdir = "wiki"))
    }
}
