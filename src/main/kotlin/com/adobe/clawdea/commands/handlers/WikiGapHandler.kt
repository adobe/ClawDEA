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
package com.adobe.clawdea.commands.handlers

import com.adobe.clawdea.knowledge.drift.ProbeMiss
import java.time.Duration
import java.time.Instant

data class WikiGapCluster(
    val tokens: Set<String>,
    val missCount: Int,
    val suggestedSlug: String,
)

object WikiGapHandler {

    fun cluster(probeMisses: List<ProbeMiss>, maxClusters: Int = 5, windowDays: Long = 7): List<WikiGapCluster> {
        val cutoff = Instant.now().minus(Duration.ofDays(windowDays))
        val recent = probeMisses.filter { miss ->
            try { Instant.parse(miss.recordedAt).isAfter(cutoff) } catch (_: Exception) { false }
        }
        if (recent.isEmpty()) return emptyList()

        val tokenSets = recent.map { it.pathTokens.map { t -> t.lowercase() }.toSet() }
            .filter { it.isNotEmpty() }
        if (tokenSets.isEmpty()) return emptyList()

        val clusters = mutableListOf<Pair<MutableSet<String>, Int>>()
        for (ts in tokenSets) {
            val match = clusters.firstOrNull { jaccard(it.first, ts) >= 0.5 }
            if (match != null) {
                match.first.retainAll(ts.union(match.first))
                clusters[clusters.indexOf(match)] = match.first to (match.second + 1)
            } else {
                clusters.add(ts.toMutableSet() to 1)
            }
        }

        return clusters
            .sortedByDescending { it.second }
            .take(maxClusters)
            .map { (tokens, count) ->
                WikiGapCluster(
                    tokens = tokens,
                    missCount = count,
                    suggestedSlug = tokens.sorted().joinToString("-").take(60),
                )
            }
    }

    fun formatOutput(clusters: List<WikiGapCluster>): String {
        if (clusters.isEmpty()) return "No wiki gaps detected in the last 7 days."
        val sb = StringBuilder("Top wiki gaps (last 7 days):\n\n")
        for ((i, c) in clusters.withIndex()) {
            val tokensStr = c.tokens.sorted().joinToString(", ") { it }
            sb.appendLine("${i + 1}. [$tokensStr] — ${c.missCount} probe miss${if (c.missCount > 1) "es" else ""} — consider concept page: ${c.suggestedSlug}")
        }
        return sb.toString().trimEnd()
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return intersection.toDouble() / union.toDouble()
    }
}
