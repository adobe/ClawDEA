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

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class WorkspaceManifestFilterTest {
    @Test fun `current project in group A returns only group A`() {
        val tmp = Files.createTempDirectory("ws-filter")
        val a = Files.createDirectory(tmp.resolve("a"))
        Files.createDirectory(tmp.resolve("b")); Files.createDirectory(tmp.resolve("c"))
        val m = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "role-a"), RepoEntry("b", "b", "role-b"))),
            RepoGroup("g2", listOf(RepoEntry("c", "c", "role-c"))),
        ), discoveredAt = tmp)
        val out = WorkspaceManifestFilter.filterToCurrentGroup(m, tmp, a)
        assertEquals(listOf("g1"), out.groups.map { it.name })
        assertEquals(listOf("a", "b"), out.groups[0].repos.map { it.key })
    }
    @Test fun `current project not in any group returns all groups`() {
        val tmp = Files.createTempDirectory("ws-filter")
        Files.createDirectory(tmp.resolve("a"))
        val outsider = Files.createDirectory(tmp.resolve("outsider"))
        val m = WorkspaceManifest("ws", listOf(RepoGroup("g1", listOf(RepoEntry("a", "a", "role-a")))), discoveredAt = tmp)
        assertEquals(m.groups, WorkspaceManifestFilter.filterToCurrentGroup(m, tmp, outsider).groups)
    }
    @Test fun `null basePath returns all groups`() {
        val tmp = Files.createTempDirectory("ws-filter")
        val m = WorkspaceManifest("ws", listOf(RepoGroup("g1", listOf(RepoEntry("a", "a", "role-a")))), discoveredAt = tmp)
        assertEquals(m.groups, WorkspaceManifestFilter.filterToCurrentGroup(m, tmp, null).groups)
    }
    @Test fun `transitive closure includes direct deps in DFS preorder`() {
        val tmp = Files.createTempDirectory("ws-closure")
        val a = Files.createDirectory(tmp.resolve("a"))
        Files.createDirectory(tmp.resolve("b")); Files.createDirectory(tmp.resolve("c"))
        val m = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "r")), dependsOn = listOf("g2")),
            RepoGroup("g2", listOf(RepoEntry("b", "b", "r"))),
            RepoGroup("g3", listOf(RepoEntry("c", "c", "r"))),
        ), discoveredAt = tmp)
        val out = WorkspaceManifestFilter.filterToCurrentGroup(m, tmp, a)
        assertEquals(listOf("g1", "g2"), out.groups.map { it.name })
    }
    @Test fun `transitive closure walks deeper deps`() {
        val tmp = Files.createTempDirectory("ws-closure")
        val a = Files.createDirectory(tmp.resolve("a"))
        Files.createDirectory(tmp.resolve("b")); Files.createDirectory(tmp.resolve("c"))
        val m = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "r")), dependsOn = listOf("g2")),
            RepoGroup("g2", listOf(RepoEntry("b", "b", "r")), dependsOn = listOf("g3")),
            RepoGroup("g3", listOf(RepoEntry("c", "c", "r"))),
        ), discoveredAt = tmp)
        val out = WorkspaceManifestFilter.filterToCurrentGroup(m, tmp, a)
        assertEquals(listOf("g1", "g2", "g3"), out.groups.map { it.name })
    }
    @Test fun `cycle in dependsOn is tolerated`() {
        val tmp = Files.createTempDirectory("ws-closure")
        val a = Files.createDirectory(tmp.resolve("a"))
        Files.createDirectory(tmp.resolve("b"))
        val m = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "r")), dependsOn = listOf("g2")),
            RepoGroup("g2", listOf(RepoEntry("b", "b", "r")), dependsOn = listOf("g1")),
        ), discoveredAt = tmp)
        val out = WorkspaceManifestFilter.filterToCurrentGroup(m, tmp, a)
        assertEquals(listOf("g1", "g2"), out.groups.map { it.name })
    }
    @Test fun `unknown dep group name is skipped`() {
        val tmp = Files.createTempDirectory("ws-closure")
        val a = Files.createDirectory(tmp.resolve("a"))
        Files.createDirectory(tmp.resolve("b"))
        val m = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "r")), dependsOn = listOf("missing", "g2")),
            RepoGroup("g2", listOf(RepoEntry("b", "b", "r"))),
        ), discoveredAt = tmp)
        val out = WorkspaceManifestFilter.filterToCurrentGroup(m, tmp, a)
        assertEquals(listOf("g1", "g2"), out.groups.map { it.name })
    }
}
