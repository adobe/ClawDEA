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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.FileSystems
import java.nio.file.Files

class SiblingsRendererTest {

    @Test
    fun `renders header and one bullet per sibling`() {
        val fs = FileSystems.getDefault()
        val manifest = WorkspaceManifest(
            name = "acme",
            groups = listOf(
                RepoGroup(
                    RepoGroup.DEFAULT_NAME,
                    listOf(
                        RepoEntry("modules", "modules", "Modules (live copies, blueprints, rollouts)", listOf("SITES", "CQ")),
                        RepoEntry("frontend", "../frontend", "Core frontend"),
                    ),
                ),
            ),
            discoveredAt = fs.getPath("/tmp/acme"),
        )

        val text = SiblingsRenderer.render(manifest, currentRepoKey = "modules")

        assertTrue(text.contains("# Workspace: acme"))
        // Current repo is annotated, not omitted.
        assertTrue(text.contains("**modules** (this repo)"))
        // Sibling appears with role.
        assertTrue(text.contains("**frontend**"))
        assertTrue(text.contains("Core frontend"))
        // Wiki path hint is included so Claude knows where to look.
        assertTrue(text.contains("/tmp/frontend/.clawdea/wiki/"))
    }

    @Test
    fun `omits manifest dir hint when discoveredAt is null`() {
        val manifest = WorkspaceManifest(
            name = "ws",
            groups = listOf(RepoGroup(RepoGroup.DEFAULT_NAME, listOf(RepoEntry("a", "a", "Role")))),
            discoveredAt = null,
        )

        val text = SiblingsRenderer.render(manifest, currentRepoKey = null)

        assertTrue(text.contains("# Workspace: ws"))
        assertTrue(text.contains("**a**"))
        assertFalse(text.contains(".claude/wiki/"))
    }

    @Test
    fun `empty repos list still emits header and explanation`() {
        val manifest = WorkspaceManifest(name = "ws", groups = emptyList())
        val text = SiblingsRenderer.render(manifest, currentRepoKey = null)
        assertTrue(text.contains("# Workspace: ws"))
        assertTrue(text.contains("(no sibling repos"))
    }

    @Test
    fun `multi-group manifest renders only the current group's repos`() {
        val tmp = Files.createTempDirectory("siblings-test")
        val m = WorkspaceManifest(
            "ws",
            listOf(
                RepoGroup("g1", listOf(RepoEntry("a", "a", "role-a"), RepoEntry("b", "b", "role-b"))),
                RepoGroup("g2", listOf(RepoEntry("c", "c", "role-c"))),
            ),
            discoveredAt = tmp,
        )
        val filtered = m.copy(groups = listOf(m.groups[0]))
        val out = SiblingsRenderer.render(filtered, currentRepoKey = "a")
        assertTrue(out.contains("**a**") || out.contains("(this repo)"))
        assertTrue(out.contains("**b**"))
        assertFalse("c should not appear (other group)", out.contains("**c**"))
    }

    @Test
    fun `empty manifest renders the no-repos hint`() {
        val out = SiblingsRenderer.render(WorkspaceManifest("ws", emptyList()), null)
        assertTrue(out.contains("no sibling repos"))
    }

    @Test
    fun `transitive group repos get via annotation`() {
        val tmp = Files.createTempDirectory("siblings-test")
        val m = WorkspaceManifest("ws", listOf(
            RepoGroup("acme-app", listOf(RepoEntry("modules", "modules", "Modules")), dependsOn = listOf("runtime")),
            RepoGroup("runtime", listOf(RepoEntry("runtime-engine", "runtime-engine", "Runtime core"))),
        ), discoveredAt = tmp)
        val out = SiblingsRenderer.render(m, currentRepoKey = "modules", currentGroupName = "acme-app")
        assertTrue(out.contains("**runtime-engine**") && out.contains("(via acme-app → runtime)"))
    }

    @Test
    fun `sibling wiki path honors the sibling's own team config`() {
        val tmp = Files.createTempDirectory("siblings-team")
        // Sibling repo lives at <tmp>/frontend and opted into team mode with a
        // relocated wiki at docs/llm-wiki/.
        val frontend = Files.createDirectories(tmp.resolve("frontend"))
        Files.createDirectories(frontend.resolve(".clawdea"))
        Files.writeString(frontend.resolve(".clawdea").resolve("config.json"), """{"wikiPath":"docs/llm-wiki"}""")

        val m = WorkspaceManifest(
            "ws",
            listOf(
                RepoGroup(
                    RepoGroup.DEFAULT_NAME,
                    listOf(
                        RepoEntry("modules", "modules", "Modules"),
                        RepoEntry("frontend", "frontend", "Frontend"),
                    ),
                ),
            ),
            discoveredAt = tmp,
        )
        val out = SiblingsRenderer.render(m, currentRepoKey = "modules")
        assertTrue(
            "frontend wiki should resolve to its team-mode docs/llm-wiki dir",
            out.contains(frontend.resolve("docs").resolve("llm-wiki").toString()),
        )
        // modules has no config → default .clawdea/wiki.
        assertTrue(out.contains(tmp.resolve("modules").resolve(".clawdea").resolve("wiki").toString()))
    }

    @Test
    fun `current group repos get no via annotation`() {
        val tmp = Files.createTempDirectory("siblings-test")
        val m = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(
                RepoEntry("modules", "modules", "Modules"),
                RepoEntry("frontend", "frontend", "Frontend"),
            )),
        ), discoveredAt = tmp)
        val out = SiblingsRenderer.render(m, currentRepoKey = "modules", currentGroupName = "g1")
        assertFalse("no (via …) expected in current group", out.contains("(via "))
    }
}
