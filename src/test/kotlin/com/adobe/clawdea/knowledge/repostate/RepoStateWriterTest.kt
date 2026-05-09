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
package com.adobe.clawdea.knowledge.repostate

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class RepoStateWriterTest {

    private lateinit var tmp: Path

    @Before
    fun setup() {
        tmp = Files.createTempDirectory("clawdea-repostatewriter-")
    }

    @After
    fun teardown() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `write creates the configured dir and file`() {
        val target = tmp.resolve(".claude/REPO_STATE.md")
        assertFalse(Files.exists(target))

        RepoStateWriter.write(projectRoot = tmp, claudeDirName = ".claude", content = "# Hello\n")

        assertTrue(Files.exists(target))
        assertEquals("# Hello\n", Files.readString(target))
    }

    @Test
    fun `write overwrites existing file`() {
        val target = tmp.resolve(".claude/REPO_STATE.md")
        Files.createDirectories(target.parent)
        Files.writeString(target, "# Old\n")

        RepoStateWriter.write(projectRoot = tmp, claudeDirName = ".claude", content = "# New\n")

        assertEquals("# New\n", Files.readString(target))
    }

    @Test
    fun `write does not leave temp file on success`() {
        RepoStateWriter.write(projectRoot = tmp, claudeDirName = ".claude", content = "# X\n")

        val claudeDir = tmp.resolve(".claude")
        val tempFiles = Files.list(claudeDir).use { stream ->
            stream.filter { it.fileName.toString().startsWith("REPO_STATE.md.tmp") }.toList()
        }
        assertTrue("expected no leftover temp files, found: $tempFiles", tempFiles.isEmpty())
    }

    @Test
    fun `write to a custom dir name works`() {
        RepoStateWriter.write(projectRoot = tmp, claudeDirName = ".clawdea", content = "# X\n")
        assertTrue(Files.exists(tmp.resolve(".clawdea/REPO_STATE.md")))
    }
}
