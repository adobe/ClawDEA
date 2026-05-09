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

object CandidateClusterer {
    private const val JIRA_JACCARD_THRESHOLD = 0.3
    private const val MIN_SIGNAL_MATCHES = 2
    private const val EVIDENCE_CAP = 5

    data class CrossGroupDep(val from: String, val to: String, val evidence: List<String>)

    fun discoverCrossGroupDeps(
        groups: List<RepoGroup>,
        fingerprintsByKey: Map<String, CandidateFingerprint>,
    ): List<CrossGroupDep> {
        val out = mutableListOf<CrossGroupDep>()
        for (gA in groups) for (gB in groups) {
            if (gA.name == gB.name) continue
            val evidence = mutableListOf<String>()
            for (a in gA.repos) {
                val fpA = fingerprintsByKey[a.key] ?: continue
                for (b in gB.repos) {
                    val fpB = fingerprintsByKey[b.key] ?: continue
                    evidence += signalAlpha(a.key, b.key, fpA, fpB)
                    evidence += signalBeta(a.key, b.key, fpA, fpB)
                    evidence += signalGamma(a.key, b.key, fpA, fpB)
                }
            }
            if (evidence.isNotEmpty()) out += CrossGroupDep(gA.name, gB.name, capEvidence(evidence))
        }
        return out
    }

    private fun capEvidence(evidence: List<String>): List<String> {
        if (evidence.size <= EVIDENCE_CAP) return evidence
        val excess = evidence.size - EVIDENCE_CAP
        return evidence.take(EVIDENCE_CAP) + "+$excess more"
    }

    private fun signalAlpha(
        aKey: String, bKey: String, fpA: CandidateFingerprint, fpB: CandidateFingerprint,
    ): List<String> {
        if (fpB.pomArtifactIds.isEmpty()) {
            // Backward compat: when Task-2 fields aren't populated, fall back to root-artifact match.
            if (fpB.pomArtifactId != null && fpA.pomDeps.any { it.endsWith(":${fpB.pomArtifactId}") }) {
                return listOf("$aKey imports artifactId ${fpB.pomArtifactId} (root) → $bKey")
            }
            return emptyList()
        }
        val out = mutableListOf<String>()
        for (dep in fpA.pomDeps) {
            val artifact = dep.substringAfterLast(':')
            if (artifact in fpB.pomArtifactIds) {
                out += "$aKey imports $dep (matches $bKey submodule artifactId $artifact)"
            }
        }
        return out
    }

    private fun signalBeta(
        aKey: String, bKey: String, fpA: CandidateFingerprint, fpB: CandidateFingerprint,
    ): List<String> {
        if (fpB.pomGroupIds.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        for (dep in fpA.pomDeps) {
            val groupId = dep.substringBefore(':', missingDelimiterValue = "")
            if (groupId.isEmpty()) continue
            if (groupId in fpB.pomGroupIds) {
                out += "$aKey imports $dep (groupId $groupId published by $bKey)"
            }
        }
        return out
    }

    private fun signalGamma(
        aKey: String, bKey: String, fpA: CandidateFingerprint, fpB: CandidateFingerprint,
    ): List<String> {
        if (fpA.javaImports.isEmpty() || fpB.packageRoots.isEmpty()) return emptyList()
        val overlap = fpA.javaImports.intersect(fpB.packageRoots)
        if (overlap.isEmpty()) return emptyList()
        return overlap.map { pkg -> "$aKey source imports $pkg (matches $bKey package roots)" }
    }

    fun cluster(candidates: List<CandidateFingerprint>): List<RepoGroup> {
        val parent = IntArray(candidates.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(i: Int, j: Int) { val ri = find(i); val rj = find(j); if (ri != rj) parent[ri] = rj }

        for (i in candidates.indices) for (j in i + 1 until candidates.size) {
            if (signalMatches(candidates[i], candidates[j]) >= MIN_SIGNAL_MATCHES) union(i, j)
        }

        val grouped = candidates.indices.groupBy { find(it) }
        val groups = grouped.values.map { idxs ->
            val members = idxs.sorted().map { candidates[it] }
            val name = inferGroupName(members)
            val repos = members.map { fp ->
                RepoEntry(fp.key, fp.path.fileName.toString(), "",
                    fp.jiraPrefixes.entries.sortedByDescending { it.value }.take(3).map { it.key })
            }
            RepoGroup(name, repos)
        }
        return groups
            .sortedWith(compareByDescending<RepoGroup> { it.repos.size }.thenBy { it.name })
            .mapIndexed { i, g -> g.copy(name = g.name.ifEmpty { "group-${i + 1}" }) }
    }

    internal fun signalMatches(a: CandidateFingerprint, b: CandidateFingerprint): Int {
        var n = 0
        if (a.packageRoots.intersect(b.packageRoots).isNotEmpty()) n++
        if (depsCrossReference(a, b)) n++
        if (jiraJaccard(a.jiraPrefixes.keys, b.jiraPrefixes.keys) >= JIRA_JACCARD_THRESHOLD) n++
        if (a.gitRemoteOrg != null && a.gitRemoteOrg == b.gitRemoteOrg) n++
        return n
    }

    private fun depsCrossReference(a: CandidateFingerprint, b: CandidateFingerprint): Boolean {
        if (a.pomArtifactId != null && b.pomDeps.any { it.endsWith(":${a.pomArtifactId}") }) return true
        if (b.pomArtifactId != null && a.pomDeps.any { it.endsWith(":${b.pomArtifactId}") }) return true
        return false
    }

    private fun jiraJaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return if (union == 0.0) 0.0 else inter / union
    }

    private fun inferGroupName(members: List<CandidateFingerprint>): String {
        if (members.size == 1) return members[0].key

        // Tier 1: inbound pom-dep ranking
        val inboundCount = members.associate { fp ->
            fp.key to members.count { other ->
                other !== fp && fp.pomArtifactId != null &&
                    other.pomDeps.any { it.endsWith(":${fp.pomArtifactId}") }
            }
        }
        val maxInbound = inboundCount.values.maxOrNull() ?: 0
        if (maxInbound > 0) {
            return members.filter { inboundCount[it.key] == maxInbound }
                .map { it.key }
                .minOrNull()!!  // alphabetical tie-break; non-empty by construction
        }

        // Tier 2: most-common-token fallback (existing behavior)
        val tokens = members.flatMap { it.key.split('-') }
            .filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount()
        return tokens.entries.filter { it.value >= 2 }.maxByOrNull { it.value }?.key ?: ""
    }
}
