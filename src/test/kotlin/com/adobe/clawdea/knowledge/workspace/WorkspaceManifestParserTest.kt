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
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceManifestParserTest {

    @Test
    fun `parses a basic manifest`() {
        val text = """
            |# Workspace: acme
            |
            |Optional prose.
            |
            |## Repos
            |
            |- **modules** `modules` — Modules (live copies, blueprints, rollouts). Tightly coupled to frontend at runtime. _jira: SITES, CQ_
            |- **frontend** `../frontend` — Core frontend. modules has runtime dep on `com.example.app.frontend.core.*`
            |- **content** `content` — Content packages Modules ships with
        """.trimMargin()

        val manifest = WorkspaceManifestParser.parse(text)

        assertEquals("acme", manifest.name)
        assertEquals(3, manifest.repos.size)

        val modules = manifest.repos[0]
        assertEquals("modules", modules.key)
        assertEquals("modules", modules.path)
        assertTrue(modules.role.startsWith("Modules (live copies"))
        assertEquals(listOf("SITES", "CQ"), modules.jiraPrefixes)

        val frontend = manifest.repos[1]
        assertEquals("frontend", frontend.key)
        assertEquals("../frontend", frontend.path)
        assertTrue(frontend.role.contains("com.example.app.frontend.core"))
        assertTrue(frontend.jiraPrefixes.isEmpty())

        val content = manifest.repos[2]
        assertEquals("content", content.key)
        assertEquals("content", content.path)
        assertEquals("Content packages Modules ships with", content.role)
    }

    @Test
    fun `tolerates missing Repos section`() {
        val text = "# Workspace: empty\n\nJust prose, no repos yet.\n"
        val manifest = WorkspaceManifestParser.parse(text)
        assertEquals("empty", manifest.name)
        assertTrue(manifest.repos.isEmpty())
    }

    @Test
    fun `skips malformed bullets`() {
        val text = """
            |# Workspace: acme
            |
            |## Repos
            |
            |- not a real bullet
            |- **good** `path` — Some role
            |- **bad** without backticks role here
            |- **another** `p2` — Another role
        """.trimMargin()

        val manifest = WorkspaceManifestParser.parse(text)
        assertEquals(2, manifest.repos.size)
        assertEquals("good", manifest.repos[0].key)
        assertEquals("another", manifest.repos[1].key)
    }

    @Test
    fun `stops at next heading`() {
        val text = """
            |# Workspace: acme
            |
            |## Repos
            |
            |- **modules** `modules` — Modules
            |
            |## Notes
            |
            |- **fake** `fake` — Should not be parsed (under Notes section)
        """.trimMargin()

        val manifest = WorkspaceManifestParser.parse(text)
        assertEquals(1, manifest.repos.size)
        assertEquals("modules", manifest.repos[0].key)
    }

    @Test
    fun `empty input yields empty manifest`() {
        val manifest = WorkspaceManifestParser.parse("")
        assertEquals("", manifest.name)
        assertTrue(manifest.repos.isEmpty())
    }

    @Test
    fun `parses jira prefixes with arbitrary spacing`() {
        val text = """
            |# Workspace: acme
            |
            |## Repos
            |
            |- **a** `a` — Role _jira:  X , Y,Z_
        """.trimMargin()

        val manifest = WorkspaceManifestParser.parse(text)
        assertEquals(listOf("X", "Y", "Z"), manifest.repos[0].jiraPrefixes)
    }

    @Test
    fun `RepoEntry resolvedPath resolves relative against manifest dir`() {
        val fs = java.nio.file.FileSystems.getDefault()
        val dir = fs.getPath("/tmp/acme")
        val entry = RepoEntry(key = "frontend", path = "../frontend", role = "x")
        val resolved = entry.resolvedPath(dir)
        assertEquals(fs.getPath("/tmp/frontend").normalize(), resolved.normalize())
    }

    @Test
    fun `RepoEntry resolvedPath leaves absolute paths alone`() {
        val fs = java.nio.file.FileSystems.getDefault()
        val dir = fs.getPath("/tmp/acme")
        val entry = RepoEntry(key = "abs", path = "/Users/me/repos/abs", role = "x")
        val resolved = entry.resolvedPath(dir)
        assertEquals(fs.getPath("/Users/me/repos/abs"), resolved)
    }

    @Test fun `unnamed Repos heading parses as default group`() {
        val text = "# Workspace: ws\n\n## Repos\n\n- **a** `a` — role-a\n"
        val m = WorkspaceManifestParser.parse(text)
        assertEquals(1, m.groups.size); assertEquals(RepoGroup.DEFAULT_NAME, m.groups[0].name)
        assertEquals(listOf("a"), m.groups[0].repos.map { it.key })
    }
    @Test fun `named Repos headings produce named groups in source order`() {
        val text = "# Workspace: ws\n\n## Repos: g1\n\n- **a** `a` — role-a\n\n## Repos: g2\n\n- **b** `b` — role-b\n- **c** `c` — role-c\n"
        val m = WorkspaceManifestParser.parse(text)
        assertEquals(listOf("g1", "g2"), m.groups.map { it.name })
        assertEquals(listOf("a"), m.groups[0].repos.map { it.key })
        assertEquals(listOf("b", "c"), m.groups[1].repos.map { it.key })
    }
    @Test fun `mixed unnamed and named groups coexist`() {
        val text = "# Workspace: ws\n\n## Repos\n\n- **a** `a` — role-a\n\n## Repos: g2\n\n- **b** `b` — role-b\n"
        val m = WorkspaceManifestParser.parse(text)
        assertEquals(listOf(RepoGroup.DEFAULT_NAME, "g2"), m.groups.map { it.name })
    }
    @Test fun `group name with extra whitespace is trimmed`() {
        val text = "# Workspace: ws\n\n## Repos:    spacey-name   \n\n- **a** `a` — role-a\n"
        assertEquals("spacey-name", WorkspaceManifestParser.parse(text).groups.single().name)
    }

    @Test fun `parses dependsOn line directly under group heading`() {
        val text = """
            # Workspace: ws

            ## Repos: acme-app
            _dependsOn: runtime, storage_

            - **a** `a` — role-a
        """.trimIndent()
        val m = WorkspaceManifestParser.parse(text)
        assertEquals(listOf("runtime", "storage"), m.groups.single().dependsOn)
        assertEquals(listOf("a"), m.groups.single().repos.map { it.key })
    }
    @Test fun `dependsOn entries are trimmed and empties dropped`() {
        val text = """
            # Workspace: ws

            ## Repos: g
            _dependsOn:  runtime ,  ,  storage  _

            - **a** `a` — r
        """.trimIndent()
        assertEquals(listOf("runtime", "storage"), WorkspaceManifestParser.parse(text).groups.single().dependsOn)
    }
    @Test fun `missing dependsOn defaults to empty`() {
        val text = """
            # Workspace: ws

            ## Repos: g

            - **a** `a` — r
        """.trimIndent()
        assertEquals(emptyList<String>(), WorkspaceManifestParser.parse(text).groups.single().dependsOn)
    }
    @Test fun `dependsOn before any Repos heading is ignored`() {
        val text = """
            # Workspace: ws

            _dependsOn: runtime_

            ## Repos: g

            - **a** `a` — r
        """.trimIndent()
        assertEquals(emptyList<String>(), WorkspaceManifestParser.parse(text).groups.single().dependsOn)
    }
    @Test fun `multiple groups each have their own dependsOn`() {
        val text = """
            # Workspace: ws

            ## Repos: a
            _dependsOn: b_

            - **x** `x` — r

            ## Repos: b

            - **y** `y` — r
        """.trimIndent()
        val m = WorkspaceManifestParser.parse(text)
        assertEquals(listOf("b"), m.groups[0].dependsOn)
        assertEquals(emptyList<String>(), m.groups[1].dependsOn)
    }

    @Test fun `parses two-line group shape with name on preceding heading`() {
        val text = """
            # Workspace: ws

            ## acme-app

            ## Repos:
            _dependsOn: runtime_

            - **modules** `modules` — Modules
        """.trimIndent()
        val m = WorkspaceManifestParser.parse(text)
        assertEquals(listOf("acme-app"), m.groups.map { it.name })
        assertEquals(listOf("runtime"), m.groups.single().dependsOn)
        assertEquals(listOf("modules"), m.groups.single().repos.map { it.key })
    }

    @Test fun `parses two-line group shape with no trailing colon on Repos`() {
        val text = """
            # Workspace: ws

            ## acme-app

            ## Repos

            - **modules** `modules` — Modules
        """.trimIndent()
        val m = WorkspaceManifestParser.parse(text)
        assertEquals(listOf("acme-app"), m.groups.map { it.name })
        assertEquals(listOf("modules"), m.groups.single().repos.map { it.key })
    }

    @Test fun `bare Repos heading falls back to default when no preceding name`() {
        val text = """
            # Workspace: ws

            ## Repos:

            - **a** `a` — role-a
        """.trimIndent()
        val m = WorkspaceManifestParser.parse(text)
        assertEquals(RepoGroup.DEFAULT_NAME, m.groups.single().name)
        assertEquals(listOf("a"), m.groups.single().repos.map { it.key })
    }

    @Test fun `single-line Repos heading still works in mixed manifest`() {
        val text = """
            # Workspace: ws

            ## Repos: g1

            - **a** `a` — role-a

            ## g2

            ## Repos:

            - **b** `b` — role-b
        """.trimIndent()
        val m = WorkspaceManifestParser.parse(text)
        assertEquals(listOf("g1", "g2"), m.groups.map { it.name })
    }
}
