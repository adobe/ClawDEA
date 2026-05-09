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
package com.adobe.clawdea.knowledge.wiki

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths

class WikiSearcherTest {
    private val wiki = WikiPath(Paths.get("src/test/testData/wikifixtures/sample-wiki"))
    private val searcher = WikiSearcher(wiki)

    @Test fun `finds substring matches across pages`() {
        val results = searcher.search("rollout")
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.relativePath == "concepts/rollout-flow.md" })
    }

    @Test fun `ranks pages with more matches higher`() {
        val results = searcher.search("rollout")
        val concept = results.find { it.relativePath == "concepts/rollout-flow.md" }!!
        val index = results.find { it.relativePath == "index.md" }!!
        assertTrue(concept.matchCount >= index.matchCount)
        assertTrue(results.indexOf(concept) <= results.indexOf(index))
    }

    @Test fun `case insensitive`() {
        assertTrue(searcher.search("ROLLOUT").isNotEmpty())
        assertTrue(searcher.search("rollOut").isNotEmpty())
    }

    @Test fun `returns empty when no matches`() {
        assertTrue(searcher.search("nonexistent-zzz").isEmpty())
    }

    @Test fun `match contains line number and snippet`() {
        val results = searcher.search("CompositeTreeWalker")
        val hit = results.find { it.relativePath == "concepts/composite-cf.md" }!!
        assertTrue(hit.snippet.contains("CompositeTreeWalker"))
        assertTrue(hit.firstHitLine > 0)
    }
}
