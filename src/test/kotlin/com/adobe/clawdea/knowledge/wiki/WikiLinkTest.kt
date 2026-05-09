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

import org.junit.Assert.assertEquals
import org.junit.Test

class WikiLinkTest {
    @Test fun `extracts old wikilinks and markdown concept links`() {
        val links = WikiLink.extractConceptLinks(
            pageRelativePath = "concepts/rollout-flow.md",
            text = "See [[composite-cf]] and [Primer](primer-service.md).",
        )
        assertEquals(setOf("composite-cf", "primer-service"), links.map { it.targetSlug }.toSet())
    }

    @Test fun `extracts markdown concept links from index and source pages`() {
        val indexLinks = WikiLink.extractConceptLinks(
            pageRelativePath = "index.md",
            text = "See [Rollout](concepts/rollout-flow.md).",
        )
        val sourceLinks = WikiLink.extractConceptLinks(
            pageRelativePath = "sources/runbook.md",
            text = "See [Composite](../concepts/composite-cf.md).",
        )

        assertEquals(listOf("rollout-flow"), indexLinks.map { it.targetSlug })
        assertEquals(listOf("composite-cf"), sourceLinks.map { it.targetSlug })
    }

    @Test fun `ignores markdown links outside wiki concepts`() {
        val links = WikiLink.extractConceptLinks(
            pageRelativePath = "sources/runbook.md",
            text = """
                [Index](../index.md)
                [Docs](../docs/foo.md)
                [External](https://example.com/foo.md)
                [Sibling Source](foo.md)
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), links.map { it.targetSlug })
    }

    @Test fun `normalizes index wikilink to markdown link`() {
        assertEquals(
            "[Rollout Flow](concepts/rollout-flow.md)",
            WikiLink.toMarkdownLink(fromPage = "index.md", targetSlug = "rollout-flow"),
        )
    }

    @Test fun `normalizes concept wikilink to sibling markdown link`() {
        assertEquals(
            "[Composite Cf](composite-cf.md)",
            WikiLink.toMarkdownLink(fromPage = "concepts/rollout-flow.md", targetSlug = "composite-cf"),
        )
    }
}
