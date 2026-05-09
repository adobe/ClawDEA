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
package com.adobe.clawdea.mcp

import com.adobe.clawdea.knowledge.workspace.RepoEntry
import com.adobe.clawdea.knowledge.workspace.RepoGroup
import com.adobe.clawdea.knowledge.workspace.WorkspaceManifest
import org.junit.Assert.assertEquals
import org.junit.Test

class McpWorkspaceToolsLabelTest {
    @Test fun `current group gets 'Current group' label`() {
        val m = WorkspaceManifest("ws", listOf(RepoGroup("g1", listOf(RepoEntry("a", "a", "r")))))
        assertEquals("Current group", McpWorkspaceTools.labelsForClosure(m, "g1")["g1"])
    }
    @Test fun `direct dep gets transitive label`() {
        val m = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "r")), dependsOn = listOf("g2")),
            RepoGroup("g2", listOf(RepoEntry("b", "b", "r"))),
        ))
        val labels = McpWorkspaceTools.labelsForClosure(m, "g1")
        assertEquals("Current group", labels["g1"])
        assertEquals("Depends on (transitive)", labels["g2"])
    }
    @Test fun `indirect dep gets via label`() {
        val m = WorkspaceManifest("ws", listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", "r")), dependsOn = listOf("g2")),
            RepoGroup("g2", listOf(RepoEntry("b", "b", "r")), dependsOn = listOf("g3")),
            RepoGroup("g3", listOf(RepoEntry("c", "c", "r"))),
        ))
        val labels = McpWorkspaceTools.labelsForClosure(m, "g1")
        assertEquals("Current group", labels["g1"])
        assertEquals("Depends on (transitive)", labels["g2"])
        assertEquals("Depends on (via g2)", labels["g3"])
    }
}
