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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class CandidateClustererTest {
    private fun fp(key: String, pkgRoots: Set<String> = emptySet(), pomArt: String? = null,
        pomDeps: Set<String> = emptySet(), jira: Map<String, Int> = emptyMap(), remote: String? = null) =
        CandidateFingerprint(
            path = Paths.get("/$key"), key = key, packageRoots = pkgRoots,
            pomArtifactId = pomArt, pomDeps = pomDeps,
            jiraPrefixes = jira, gitRemoteOrg = remote,
        )

    @Test fun `unrelated candidates land in separate groups`() {
        val a = fp("a", pkgRoots = setOf("com.example.clawdea"), remote = "github.com/Example-Org")
        val b = fp("b", pkgRoots = setOf("com.example.app"),         remote = "git.example.com/platform")
        assertEquals(setOf(setOf("a"), setOf("b")),
            CandidateClusterer.cluster(listOf(a, b)).map { it.repos.map { r -> r.key }.toSet() }.toSet())
    }
    @Test fun `two-of-four signals merge a pair`() {
        val a = fp("a", pkgRoots = setOf("com.example.app"), remote = "g/o")
        val b = fp("b", pkgRoots = setOf("com.example.app"), remote = "g/o")
        assertEquals(setOf(setOf("a", "b")),
            CandidateClusterer.cluster(listOf(a, b)).map { it.repos.map { r -> r.key }.toSet() }.toSet())
    }
    @Test fun `single signal is not enough`() {
        val a = fp("a", pkgRoots = setOf("com.example.app")); val b = fp("b", pkgRoots = setOf("com.example.app"))
        assertEquals(setOf(setOf("a"), setOf("b")),
            CandidateClusterer.cluster(listOf(a, b)).map { it.repos.map { r -> r.key }.toSet() }.toSet())
    }
    @Test fun `pom dep cross-reference counts as a signal`() {
        val modules = fp("modules", pomArt = "acme-modules-core", pomDeps = setOf("com.example.app:frontend-core"), remote = "g/o")
        val frontend = fp("frontend", pomArt = "frontend-core", remote = "g/o")
        assertEquals(setOf(setOf("modules", "frontend")),
            CandidateClusterer.cluster(listOf(modules, frontend)).map { it.repos.map { r -> r.key }.toSet() }.toSet())
    }
    @Test fun `jira jaccard above threshold counts as a signal`() {
        val a = fp("a", jira = mapOf("SITES" to 5, "CQ" to 3), remote = "g/o")
        val b = fp("b", jira = mapOf("SITES" to 7, "CQ" to 2), remote = "g/o")
        val c = fp("c", jira = mapOf("FOO" to 1))
        assertEquals(setOf(setOf("a", "b"), setOf("c")),
            CandidateClusterer.cluster(listOf(a, b, c)).map { it.repos.map { r -> r.key }.toSet() }.toSet())
    }
    @Test fun `transitive merges work`() {
        val a = fp("a", pkgRoots = setOf("com.x"), remote = "g/o")
        val b = fp("b", pkgRoots = setOf("com.x"), remote = "g/o")
        val c = fp("c", pkgRoots = setOf("com.x"), remote = "g/o")
        assertEquals(setOf(setOf("a", "b", "c")),
            CandidateClusterer.cluster(listOf(a, b, c)).map { it.repos.map { r -> r.key }.toSet() }.toSet())
    }
    @Test fun `group naming uses the most common shared token`() {
        val a = fp("acme-modules", pkgRoots = setOf("com.example.app"), remote = "g/o")
        val b = fp("acme-frontend", pkgRoots = setOf("com.example.app"), remote = "g/o")
        assertTrue(CandidateClusterer.cluster(listOf(a, b)).single().name.contains("acme"))
    }
    @Test fun `inbound-dep count beats token frequency`() {
        val modules = fp("acme-modules", pomArt = "acme-modules-core", pomDeps = setOf("com.example.app:frontend-core"), remote = "g/o")
        val deploy = fp("acme-deploy", pomArt = "acme-deploy-core", pomDeps = setOf("com.example.app:frontend-core"), remote = "g/o")
        val frontend = fp("acme-frontend", pomArt = "frontend-core", remote = "g/o")
        val groups = CandidateClusterer.cluster(listOf(modules, deploy, frontend))
        val clustered = groups.single { it.repos.size == 3 }
        assertEquals("acme-frontend", clustered.name)
    }
    @Test fun `inbound-dep ties broken alphabetically`() {
        val a = fp("acme-a", pomArt = "art-a", pomDeps = setOf("com.x:art-c"), remote = "g/o")
        val b = fp("acme-b", pomArt = "art-b", pomDeps = setOf("com.x:art-c"), remote = "g/o")
        val c = fp("acme-c", pomArt = "art-c", pomDeps = setOf("com.x:art-d"), remote = "g/o")
        val d = fp("acme-d", pomArt = "art-d", remote = "g/o")
        val groups = CandidateClusterer.cluster(listOf(a, b, c, d))
        val clustered = groups.single { it.repos.size == 4 }
        assertEquals("acme-c", clustered.name)
    }
    @Test fun `falls back to token frequency when no inbound deps`() {
        val a = fp("acme-toolkit-x", remote = "g/o", jira = mapOf("CLAW" to 5))
        val b = fp("acme-toolkit-y", remote = "g/o", jira = mapOf("CLAW" to 5))
        val groups = CandidateClusterer.cluster(listOf(a, b))
        val name = groups.single().name
        assertTrue("expected fallback heuristic, got '$name'", name == "acme" || name == "toolkit")
    }
    @Test fun `discovers cross-group dep when artifactId references span groups`() {
        val modules = fp("acme-modules", pomArt = "acme-modules-core", pomDeps = setOf("com.example.runtime:runtime-api"), remote = "g/o")
        val frontend = fp("acme-frontend", pomArt = "frontend-core", pomDeps = setOf("com.example.runtime:runtime-api"), remote = "g/o")
        val runtime = fp("runtime", pomArt = "runtime-api", remote = "x/y")
        val fingerprints = listOf(modules, frontend, runtime)
        val groups = CandidateClusterer.cluster(fingerprints)
        val byKey = fingerprints.associateBy { it.key }
        val deps = CandidateClusterer.discoverCrossGroupDeps(groups, byKey)
        assertTrue(deps.isNotEmpty())
        val edge = deps.first()
        assertNotEquals(edge.from, edge.to)
        assertTrue(deps.flatMap { it.evidence }.any { it.contains("acme-modules") })
    }
    @Test fun `no cross-group deps when artifactIds do not match`() {
        val a = fp("a", pomArt = "art-a", remote = "g/o")
        val b = fp("b", pomArt = "art-b", remote = "g/o")
        val c = fp("c", pomArt = "art-c", remote = "x/y")
        val groups = CandidateClusterer.cluster(listOf(a, b, c))
        val byKey = listOf(a, b, c).associateBy { it.key }
        assertEquals(emptyList<CandidateClusterer.CrossGroupDep>(),
            CandidateClusterer.discoverCrossGroupDeps(groups, byKey))
    }
    @Test fun `signal alpha matches A pomDeps against B submodule artifactIds`() {
        val a = fp("a", pomArt = "consumer", pomDeps = setOf("com.example:b-submodule"), remote = "g/o")
        val b = fp("b", pomArt = "b-parent", remote = "x/y")
        val bWithSubs = b.copy(pomArtifactIds = setOf("b-parent", "b-submodule"))
        val groups = CandidateClusterer.cluster(listOf(a, bWithSubs))
        val byKey = listOf(a, bWithSubs).associateBy { it.key }
        val deps = CandidateClusterer.discoverCrossGroupDeps(groups, byKey)
        assertTrue("expected α edge", deps.any { it.evidence.any { e -> e.contains("submodule") || e.contains("b-submodule") } })
    }

    @Test fun `signal beta matches A pomDeps groupId against B published groupIds`() {
        val a = fp("a", pomArt = "consumer", pomDeps = setOf("com.example.runtime:runtime-api"), remote = "g/o")
        val b = fp("b", pomArt = "runtime-engine", remote = "x/y")
        val bWithGroups = b.copy(pomGroupIds = setOf("com.example.runtime"))
        val groups = CandidateClusterer.cluster(listOf(a, bWithGroups))
        val byKey = listOf(a, bWithGroups).associateBy { it.key }
        val deps = CandidateClusterer.discoverCrossGroupDeps(groups, byKey)
        assertTrue("expected β edge", deps.any { it.evidence.any { e -> e.contains("com.example.runtime") } })
    }

    @Test fun `signal gamma matches A javaImports against B packageRoots`() {
        val a = fp("a", remote = "g/o").copy(javaImports = setOf("org.apache", "com.example.vault"))
        val b = fp("b", pkgRoots = setOf("org.apache"), remote = "x/y")
        val groups = CandidateClusterer.cluster(listOf(a, b))
        val byKey = listOf(a, b).associateBy { it.key }
        val deps = CandidateClusterer.discoverCrossGroupDeps(groups, byKey)
        assertTrue("expected γ edge", deps.any { it.evidence.any { e -> e.lowercase().contains("import") || e.contains("org.apache") } })
    }

    @Test fun `multiple signals merge into one CrossGroupDep with combined evidence`() {
        val a = fp("a", pomArt = "consumer", pomDeps = setOf("com.example.runtime:runtime-api"), remote = "g/o")
        val b = fp("b", pomArt = "runtime-parent", remote = "x/y").copy(
            pomArtifactIds = setOf("runtime-parent", "runtime-api"),
            pomGroupIds = setOf("com.example.runtime"),
        )
        val groups = CandidateClusterer.cluster(listOf(a, b))
        val byKey = listOf(a, b).associateBy { it.key }
        val deps = CandidateClusterer.discoverCrossGroupDeps(groups, byKey)
        val merged = deps.first { it.from != it.to && it.evidence.size >= 2 }
        assertTrue("evidence should mention both α and β", merged.evidence.size >= 2)
    }

    @Test fun `evidence list per CrossGroupDep is capped at 5 plus more-suffix`() {
        // Construct a candidate A with many pomDeps that all match B's submodule artifactIds → many α evidence lines.
        val aDeps = setOf(
            "com.example:b-sub-1", "com.example:b-sub-2", "com.example:b-sub-3",
            "com.example:b-sub-4", "com.example:b-sub-5", "com.example:b-sub-6",
            "com.example:b-sub-7", "com.example:b-sub-8",
        )
        val a = fp("a", pomArt = "consumer", pomDeps = aDeps, remote = "g/o")
        val bWithSubs = fp("b", pomArt = "b-parent", remote = "x/y").copy(
            pomArtifactIds = setOf("b-parent", "b-sub-1", "b-sub-2", "b-sub-3", "b-sub-4", "b-sub-5", "b-sub-6", "b-sub-7", "b-sub-8")
        )
        val groups = CandidateClusterer.cluster(listOf(a, bWithSubs))
        val byKey = listOf(a, bWithSubs).associateBy { it.key }
        val deps = CandidateClusterer.discoverCrossGroupDeps(groups, byKey)
        val edge = deps.first { it.from != it.to }
        // Cap = 5 evidence lines plus one "+N more" suffix
        assertEquals(6, edge.evidence.size)
        assertTrue("last line should be +N more, got '${edge.evidence.last()}'",
            edge.evidence.last().startsWith("+") && edge.evidence.last().contains("more"))
    }

    @Test fun `evidence list under cap has no more-suffix`() {
        val a = fp("a", pomArt = "consumer", pomDeps = setOf("com.example:b-sub-1"), remote = "g/o")
        val bWithSubs = fp("b", pomArt = "b-parent", remote = "x/y").copy(
            pomArtifactIds = setOf("b-parent", "b-sub-1")
        )
        val groups = CandidateClusterer.cluster(listOf(a, bWithSubs))
        val byKey = listOf(a, bWithSubs).associateBy { it.key }
        val deps = CandidateClusterer.discoverCrossGroupDeps(groups, byKey)
        val edge = deps.first { it.from != it.to }
        assertTrue("no more-suffix expected for ${edge.evidence.size} lines: ${edge.evidence}",
            edge.evidence.none { it.startsWith("+") && it.contains("more") })
    }
}
