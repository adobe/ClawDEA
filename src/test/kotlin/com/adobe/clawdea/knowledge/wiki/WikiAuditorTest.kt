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

class WikiAuditorTest {
    private val wiki = WikiPath(Paths.get("src/test/testData/wikifixtures/auditable-wiki"))
    private val auditor = WikiAuditor(wiki)

    @Test fun `detects orphan concept pages`() {
        val report = auditor.audit()
        val orphans = report.orphans.toSet()
        assertTrue("composite-cf should be orphan", "concepts/composite-cf.md" in orphans)
        assertTrue("orphan should be orphan", "concepts/orphan.md" in orphans)
        assertFalse("rollout-flow is linked from index", "concepts/rollout-flow.md" in orphans)
    }

    @Test fun `detects broken wikilinks`() {
        val report = auditor.audit()
        assertEquals(1, report.brokenLinks.size)
        val broken = report.brokenLinks.first()
        assertEquals("concepts/rollout-flow.md", broken.fromPage)
        assertEquals("missing-concept", broken.linkTarget)
    }

    @Test fun `report formats human-readable summary`() {
        val report = auditor.audit()
        val text = report.format()
        assertTrue(text.contains("orphan"))
        assertTrue(text.contains("composite-cf"))
        assertTrue(text.contains("missing-concept"))
    }

    @Test fun `clean wiki has no findings`() {
        val cleanWiki = WikiPath(Paths.get("src/test/testData/wikifixtures/sample-wiki"))
        val cleanReport = WikiAuditor(cleanWiki).audit()
        assertEquals(0, cleanReport.orphans.size)
        assertEquals(0, cleanReport.brokenLinks.size)
    }

    @Test fun `standard markdown concept links count as wiki links`() {
        val tmp = java.nio.file.Files.createTempDirectory("wiki-audit-md")
        val wikiRoot = tmp.resolve("wiki")
        java.nio.file.Files.createDirectories(wikiRoot.resolve("concepts"))
        java.nio.file.Files.writeString(wikiRoot.resolve("index.md"), "- [Rollout Flow](concepts/rollout-flow.md)")
        java.nio.file.Files.writeString(wikiRoot.resolve("concepts/rollout-flow.md"), "# Rollout Flow\n")

        val report = WikiAuditor(WikiPath(wikiRoot)).audit()

        assertEquals(emptyList<String>(), report.orphans)
        assertEquals(0, report.brokenLinks.size)
    }
}
