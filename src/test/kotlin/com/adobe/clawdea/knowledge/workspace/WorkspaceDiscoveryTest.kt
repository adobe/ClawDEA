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
package com.adobe.clawdea.knowledge.workspace

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceDiscoveryTest {

    private lateinit var tmp: Path

    @Before fun setup() { tmp = Files.createTempDirectory("clawdea-ws-discover-") }
    @After fun teardown() { tmp.toFile().deleteRecursively() }

    @Test
    fun `finds manifest in parent directory`() {
        val workspaceRoot = tmp.resolve("workspace")
        val repoDir = workspaceRoot.resolve("myrepo")
        Files.createDirectories(repoDir)
        Files.writeString(workspaceRoot.resolve(".clawdea-workspace.md"), "# Workspace: ws\n")

        val result = WorkspaceDiscovery.discover(repoDir)

        assertEquals(workspaceRoot.resolve(".clawdea-workspace.md").toRealPath(), result?.toRealPath())
    }

    @Test
    fun `nearest wins when both inner and outer manifests exist`() {
        val outer = tmp.resolve("outer")
        val inner = outer.resolve("inner")
        val repoDir = inner.resolve("repo")
        Files.createDirectories(repoDir)
        Files.writeString(outer.resolve(".clawdea-workspace.md"), "# Workspace: outer\n")
        Files.writeString(inner.resolve(".clawdea-workspace.md"), "# Workspace: inner\n")

        val result = WorkspaceDiscovery.discover(repoDir)

        assertEquals(inner.resolve(".clawdea-workspace.md").toRealPath(), result?.toRealPath())
    }

    @Test
    fun `returns null when no manifest found above project`() {
        val repoDir = tmp.resolve("solo/repo")
        Files.createDirectories(repoDir)

        val result = WorkspaceDiscovery.discover(repoDir)

        assertNull(result)
    }

    @Test
    fun `parseManifest returns null for missing path`() {
        val parsed = WorkspaceDiscovery.parseManifest(tmp.resolve("nope.md"))
        assertNull(parsed)
    }

    @Test
    fun `parseManifest reads and parses an existing file`() {
        val workspaceRoot = tmp.resolve("workspace")
        Files.createDirectories(workspaceRoot)
        val manifestPath = workspaceRoot.resolve(".clawdea-workspace.md")
        Files.writeString(
            manifestPath,
            """
                |# Workspace: ws
                |
                |## Repos
                |
                |- **a** `a` — Role
            """.trimMargin(),
        )

        val parsed = WorkspaceDiscovery.parseManifest(manifestPath)

        assertEquals("ws", parsed?.name)
        assertEquals(1, parsed?.repos?.size)
        assertEquals(manifestPath.parent.toRealPath(), parsed?.discoveredAt?.toRealPath())
    }
}
