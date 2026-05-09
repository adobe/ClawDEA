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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkspaceManifestRendererTest {

    @Test fun `round-trips a multi-group manifest`() {
        val m = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "role-a", listOf("FOO", "BAR")))),
            RepoGroup("g2", listOf(RepoEntry("b", "../b", "role-b"))),
        ))
        val parsed = WorkspaceManifestParser.parse(WorkspaceManifestRenderer.render(m))
        assertEquals(m.name, parsed.name); assertEquals(m.groups, parsed.groups)
    }

    @Test fun `default group renders without a colon-name`() {
        val m = WorkspaceManifest("ws", listOf(
            RepoGroup(RepoGroup.DEFAULT_NAME, listOf(RepoEntry("a", "a", "role-a"))),
        ))
        val out = WorkspaceManifestRenderer.render(m)
        assertTrue(out.lines().any { it == "## Repos" })
    }

    @Test fun `validateAppendOnly accepts a superset`() {
        val before = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "role-a"))),
        ))
        val after = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "role-a"), RepoEntry("b", "b", "role-b"))),
            RepoGroup("g2", listOf(RepoEntry("c", "c", "role-c"))),
        ))
        WorkspaceManifestRenderer.validateAppendOnly(before, after)  // no throw
    }

    @Test fun `validateAppendOnly rejects a mutated existing entry`() {
        val before = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "role-a"))),
        ))
        val after = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "role-a-MUTATED"))),
        ))
        try { WorkspaceManifestRenderer.validateAppendOnly(before, after); fail("expected throw") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("'a'")) }
    }

    @Test fun `validateAppendOnly rejects a removed entry`() {
        val before = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(
                RepoEntry("a", "a", "role-a"),
                RepoEntry("b", "b", "role-b"),
            )),
        ))
        val after = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "role-a"))),
        ))
        try { WorkspaceManifestRenderer.validateAppendOnly(before, after); fail("expected throw") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("'b'")) }
    }

    @Test fun `validateAppendOnly rejects a moved entry between groups`() {
        val before = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "role-a"))),
        ))
        val after = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", emptyList()),
            RepoGroup("g2", listOf(RepoEntry("a", "a", "role-a"))),
        ))
        try { WorkspaceManifestRenderer.validateAppendOnly(before, after); fail("expected throw") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("'a'") && e.message!!.contains("group")) }
    }

    @Test fun `round-trips a manifest with dependsOn edges`() {
        val m = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "r")), dependsOn = listOf("g2", "g3")),
            RepoGroup("g2", listOf(RepoEntry("b", "b", "r"))),
            RepoGroup("g3", listOf(RepoEntry("c", "c", "r"))),
        ))
        val parsed = WorkspaceManifestParser.parse(WorkspaceManifestRenderer.render(m))
        assertEquals(m.groups, parsed.groups)
    }

    @Test fun `empty dependsOn does not emit a line`() {
        val m = WorkspaceManifest("ws", listOf(RepoGroup("g", listOf(RepoEntry("a", "a", "r")))))
        assertFalse(WorkspaceManifestRenderer.render(m).contains("_dependsOn:"))
    }

    @Test fun `validateAppendOnly accepts adding dep edges`() {
        val before = WorkspaceManifest("ws", listOf(
            RepoGroup("a", listOf(RepoEntry("x", "x", "r"))),
            RepoGroup("b", listOf(RepoEntry("y", "y", "r"))),
        ))
        val after = WorkspaceManifest("ws", listOf(
            RepoGroup("a", listOf(RepoEntry("x", "x", "r")), dependsOn = listOf("b")),
            RepoGroup("b", listOf(RepoEntry("y", "y", "r"))),
        ))
        WorkspaceManifestRenderer.validateAppendOnly(before, after)
    }

    @Test fun `validateAppendOnly rejects removing dep edges`() {
        val before = WorkspaceManifest("ws", listOf(
            RepoGroup("a", listOf(RepoEntry("x", "x", "r")), dependsOn = listOf("b", "c")),
            RepoGroup("b", listOf(RepoEntry("y", "y", "r"))),
            RepoGroup("c", listOf(RepoEntry("z", "z", "r"))),
        ))
        val after = WorkspaceManifest("ws", listOf(
            RepoGroup("a", listOf(RepoEntry("x", "x", "r")), dependsOn = listOf("b")),
            RepoGroup("b", listOf(RepoEntry("y", "y", "r"))),
            RepoGroup("c", listOf(RepoEntry("z", "z", "r"))),
        ))
        try { WorkspaceManifestRenderer.validateAppendOnly(before, after); fail("expected throw") }
        catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("'c'") && e.message!!.lowercase().contains("dependson"))
        }
    }

    @Test fun `validateAppendOnly is order-insensitive on dep edges`() {
        val before = WorkspaceManifest("ws", listOf(
            RepoGroup("a", listOf(RepoEntry("x", "x", "r")), dependsOn = listOf("b", "c")),
            RepoGroup("b", listOf(RepoEntry("y", "y", "r"))),
            RepoGroup("c", listOf(RepoEntry("z", "z", "r"))),
        ))
        val after = WorkspaceManifest("ws", listOf(
            RepoGroup("a", listOf(RepoEntry("x", "x", "r")), dependsOn = listOf("c", "b")),
            RepoGroup("b", listOf(RepoEntry("y", "y", "r"))),
            RepoGroup("c", listOf(RepoEntry("z", "z", "r"))),
        ))
        WorkspaceManifestRenderer.validateAppendOnly(before, after)  // no throw
    }
}
