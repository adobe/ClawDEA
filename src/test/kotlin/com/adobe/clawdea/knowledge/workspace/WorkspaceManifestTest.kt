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
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceManifestTest {
    @Test fun `repos is the flatten of groups in source order`() {
        val a = RepoEntry("a", "a", "role-a"); val b = RepoEntry("b", "b", "role-b"); val c = RepoEntry("c", "c", "role-c")
        val manifest = WorkspaceManifest("ws", listOf(RepoGroup("g1", listOf(a, b)), RepoGroup("g2", listOf(c))))
        assertEquals(listOf(a, b, c), manifest.repos)
    }
    @Test fun `groupOf returns the group containing the given key`() {
        val a = RepoEntry("a", "a", ""); val b = RepoEntry("b", "b", "")
        val manifest = WorkspaceManifest("ws", listOf(RepoGroup("g1", listOf(a)), RepoGroup("g2", listOf(b))))
        assertEquals("g1", manifest.groupOf("a")?.name)
        assertEquals("g2", manifest.groupOf("b")?.name)
        assertNull(manifest.groupOf("missing"))
    }
    @Test fun `default group name is 'default'`() {
        assertEquals("default", RepoGroup.DEFAULT_NAME)
    }
    @Test fun `RepoGroup dependsOn defaults to empty`() {
        assertEquals(emptyList<String>(), RepoGroup("g1", emptyList()).dependsOn)
    }
    @Test fun `RepoGroup dependsOn preserves declared order`() {
        val g = RepoGroup("g1", emptyList(), dependsOn = listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), g.dependsOn)
    }
}
