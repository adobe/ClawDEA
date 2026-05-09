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
package com.adobe.clawdea.knowledge.workspace.seed

import com.adobe.clawdea.knowledge.workspace.RepoEntry
import com.adobe.clawdea.knowledge.workspace.RepoGroup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class SeedPromptBuilderTest {
    @Test fun `prompt lists each workspace-root candidate`() {
        val p = SeedPromptBuilder.build(
            roots = listOf(Paths.get("/a"), Paths.get("/b")),
            suggestedGroups = emptyList(),
            suggestedDeps = emptyList(),
            existingManifestText = null,
        )
        assertTrue(p.contains("/a")); assertTrue(p.contains("/b")); assertTrue(p.contains("AskUserQuestion"))
    }
    @Test fun `prompt lists each suggested group with member keys`() {
        val groups = listOf(
            RepoGroup("g1", listOf(RepoEntry("a", "a", ""), RepoEntry("b", "b", ""))),
            RepoGroup("g2", listOf(RepoEntry("c", "c", ""))),
        )
        val p = SeedPromptBuilder.build(
            roots = listOf(Paths.get("/ws")),
            suggestedGroups = groups,
            suggestedDeps = emptyList(),
            existingManifestText = null,
        )
        assertTrue(p.contains("g1") && p.contains("g2") && p.contains("a") && p.contains("b") && p.contains("c"))
    }
    @Test fun `prompt mentions append-only when manifest already exists`() {
        val p = SeedPromptBuilder.build(
            roots = listOf(Paths.get("/ws")),
            suggestedGroups = emptyList(),
            suggestedDeps = emptyList(),
            existingManifestText = "# Workspace: ws\n\n## Repos\n\n- **a** `a` — role-a\n",
        )
        assertTrue(p.lowercase().contains("append-only")); assertTrue(p.contains("# Workspace: ws"))
    }
    @Test fun `prompt does not mention append-only when no existing manifest`() {
        assertFalse(
            SeedPromptBuilder.build(
                roots = listOf(Paths.get("/ws")),
                suggestedGroups = emptyList(),
                suggestedDeps = emptyList(),
                existingManifestText = null,
            ).lowercase().contains("append-only"),
        )
    }
    @Test fun `prompt mentions propose_write and the manifest format wiki page`() {
        val p = SeedPromptBuilder.build(
            roots = listOf(Paths.get("/ws")),
            suggestedGroups = emptyList(),
            suggestedDeps = emptyList(),
            existingManifestText = null,
        )
        assertTrue(p.contains("propose_write") && p.contains("workspace-manifest"))
    }
    @Test fun `prompt includes suggested cross-group deps when present`() {
        val deps = listOf(CandidateClusterer.CrossGroupDep(
            from = "acme-app", to = "runtime",
            evidence = listOf("acme-modules → runtime-engine", "acme-frontend → runtime-engine"),
        ))
        val p = SeedPromptBuilder.build(
            roots = listOf(Paths.get("/ws")),
            suggestedGroups = emptyList(),
            suggestedDeps = deps,
            existingManifestText = null,
        )
        assertTrue(p.lowercase().contains("cross-group"))
        assertTrue(p.contains("acme-app") && p.contains("runtime"))
        assertTrue(p.contains("acme-modules → runtime-engine"))
    }
    @Test fun `prompt omits cross-group section when no deps`() {
        val p = SeedPromptBuilder.build(
            roots = listOf(Paths.get("/ws")),
            suggestedGroups = emptyList(),
            suggestedDeps = emptyList(),
            existingManifestText = null,
        )
        assertFalse(p.lowercase().contains("cross-group"))
    }
    @Test fun `prompt includes a worked example showing the canonical shape`() {
        val p = SeedPromptBuilder.build(
            roots = listOf(Paths.get("/ws")),
            suggestedGroups = emptyList(),
            suggestedDeps = emptyList(),
            existingManifestText = null,
        )
        // Worked example uses single-line shape
        assertTrue("expected '## Repos: acme-app' in worked example", p.contains("## Repos: acme-app"))
        assertTrue("expected example dependsOn line", p.contains("_dependsOn: runtime, storage_"))
    }

    @Test fun `prompt warns against per-repo singletons and over-fragmentation`() {
        val p = SeedPromptBuilder.build(
            roots = listOf(Paths.get("/ws")),
            suggestedGroups = emptyList(),
            suggestedDeps = emptyList(),
            existingManifestText = null,
        )
        // Some phrase that conveys the cluster-vs-edge guidance
        val lower = p.lowercase()
        assertTrue("expected guidance about clusters/larger groupings: $p",
            lower.contains("cluster") || lower.contains("singleton") || lower.contains("merge"))
    }
}
