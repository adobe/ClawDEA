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
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WikiGapHandlerTest {

    @Test fun `clusters similar token sets`() {
        val now = Instant.now().toString()
        val misses = listOf(
            ProbeMiss("policy resolution", listOf("policies", "clientlibs"), 0, "a", now),
            ProbeMiss("policy binding", listOf("policies", "clientlibs", "jcr_root"), 0, "b", now),
            ProbeMiss("template mapping", listOf("template", "page", "v2"), 0, "c", now),
        )
        val clusters = WikiGapHandler.cluster(misses)
        assertTrue(clusters.size >= 2)
        assertTrue(clusters[0].missCount >= clusters[1].missCount)
    }

    @Test fun `filters out old misses beyond window`() {
        val old = "2020-01-01T00:00:00Z"
        val misses = listOf(
            ProbeMiss("old query", listOf("policies"), 0, "a", old),
        )
        val clusters = WikiGapHandler.cluster(misses)
        assertTrue(clusters.isEmpty())
    }

    @Test fun `stable output order by miss count descending`() {
        val now = Instant.now().toString()
        val misses = listOf(
            ProbeMiss("q1", listOf("alpha"), 0, "a", now),
            ProbeMiss("q2", listOf("beta"), 0, "b", now),
            ProbeMiss("q3", listOf("beta"), 0, "c", now),
            ProbeMiss("q4", listOf("beta"), 0, "d", now),
        )
        val clusters = WikiGapHandler.cluster(misses)
        assertEquals("beta", clusters[0].tokens.first())
        assertTrue(clusters[0].missCount > clusters[1].missCount)
    }

    @Test fun `formatOutput handles empty clusters`() {
        val output = WikiGapHandler.formatOutput(emptyList())
        assertTrue(output.contains("No wiki gaps"))
    }

    @Test fun `formatOutput renders numbered list`() {
        val clusters = listOf(
            WikiGapCluster(setOf("policies", "clientlibs"), 4, "clientlibs-policies"),
            WikiGapCluster(setOf("page", "template"), 2, "page-template"),
        )
        val output = WikiGapHandler.formatOutput(clusters)
        assertTrue(output.contains("1."))
        assertTrue(output.contains("2."))
        assertTrue(output.contains("4 probe misses"))
        assertTrue(output.contains("clientlibs-policies"))
    }

    @Test fun `caps to maxClusters`() {
        val now = Instant.now().toString()
        val misses = (1..20).map {
            ProbeMiss("q$it", listOf("token$it"), 0, "h", now)
        }
        val clusters = WikiGapHandler.cluster(misses, maxClusters = 3)
        assertTrue(clusters.size <= 3)
    }
}
