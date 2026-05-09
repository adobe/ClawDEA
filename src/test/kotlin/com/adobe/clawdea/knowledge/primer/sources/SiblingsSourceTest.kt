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
package com.adobe.clawdea.knowledge.primer.sources

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SiblingsSourceTest {

    private lateinit var tmp: Path

    @Before fun setup() { tmp = Files.createTempDirectory("clawdea-siblings-source-") }
    @After fun teardown() { tmp.toFile().deleteRecursively() }

    @Test
    fun `loadFor returns null when no manifest exists`() {
        val repoDir = tmp.resolve("solo")
        Files.createDirectories(repoDir)

        val text = SiblingsSource.loadFor(
            projectBasePath = repoDir,
            claudeDirName = ".claude",
            manifestName = ".clawdea-workspace.md",
        )

        assertNull(text)
    }

    @Test
    fun `loadFor renders SIBLINGS and writes to project claude dir`() {
        val workspaceRoot = tmp.resolve("workspace")
        val repoDir = workspaceRoot.resolve("modules")
        Files.createDirectories(repoDir)
        Files.writeString(
            workspaceRoot.resolve(".clawdea-workspace.md"),
            """
                |# Workspace: acme
                |
                |## Repos
                |
                |- **modules** `modules` — Modules
                |- **frontend** `../frontend` — Core frontend
            """.trimMargin(),
        )

        val text = SiblingsSource.loadFor(
            projectBasePath = repoDir,
            claudeDirName = ".claude",
            manifestName = ".clawdea-workspace.md",
        )

        assertNotNull(text)
        assertTrue(text!!.contains("# Workspace: acme"))
        assertTrue(text.contains("**modules** (this repo)"))
        assertTrue(text.contains("**frontend**"))

        val siblingsFile = repoDir.resolve(".claude/SIBLINGS.md")
        assertTrue(Files.exists(siblingsFile))
        assertEquals(text.trim(), Files.readString(siblingsFile).trim())
    }

    @Test
    fun `loadFor handles repo whose dirname does not match any manifest key`() {
        val workspaceRoot = tmp.resolve("workspace")
        val repoDir = workspaceRoot.resolve("not-in-manifest")
        Files.createDirectories(repoDir)
        Files.writeString(
            workspaceRoot.resolve(".clawdea-workspace.md"),
            """
                |# Workspace: acme
                |
                |## Repos
                |
                |- **modules** `modules` — Modules
            """.trimMargin(),
        )

        val text = SiblingsSource.loadFor(
            projectBasePath = repoDir,
            claudeDirName = ".claude",
            manifestName = ".clawdea-workspace.md",
        )

        assertNotNull(text)
        assertTrue(text!!.contains("**modules**"))
        assertTrue(!text.contains("(this repo)"))
    }
}
