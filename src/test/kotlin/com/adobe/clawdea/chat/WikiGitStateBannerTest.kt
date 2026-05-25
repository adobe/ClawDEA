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
package com.adobe.clawdea.chat

import com.adobe.clawdea.knowledge.wiki.WikiGitStateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiGitStateBannerTest {

    private fun newBanner(
        captured: MutableList<String>,
        fixCount: IntArray = IntArray(1),
        dismissCount: IntArray = IntArray(1),
    ) = WikiGitStateBanner(
        updateHtml = { html -> captured.add(html) },
        onFix = { fixCount[0]++ },
        onDismiss = { dismissCount[0]++ },
    )

    @Test
    fun `setIssues with empty list renders hidden placeholder`() {
        val captured = mutableListOf<String>()
        val banner = newBanner(captured)

        banner.setIssues(emptyList())

        assertEquals(1, captured.size)
        assertTrue(captured.last().contains("display:none"))
        assertFalse(captured.last().contains("fix it"))
    }

    @Test
    fun `setIssues with issues renders fix and dismiss links`() {
        val captured = mutableListOf<String>()
        val banner = newBanner(captured)

        banner.setIssues(listOf(
            WikiGitStateChecker.Issue(
                relativePath = ".clawdea/config.json",
                expected = WikiGitStateChecker.Expectation.TRACKED,
                actuallyTracked = false,
                actuallyIgnored = false,
                fileExists = true,
            ),
        ))

        val html = captured.last()
        assertTrue(html.contains("data-action=\"wiki-git-state-action\""))
        assertTrue(html.contains("data-wgs-action=\"fix\""))
        assertTrue(html.contains("data-wgs-action=\"dismiss\""))
        assertTrue(html.contains("1 issue"))
    }

    @Test
    fun `setIssues with multiple issues uses plural label`() {
        val captured = mutableListOf<String>()
        val banner = newBanner(captured)

        banner.setIssues(listOf(
            WikiGitStateChecker.Issue(
                relativePath = ".clawdea/config.json",
                expected = WikiGitStateChecker.Expectation.TRACKED,
                actuallyTracked = false,
                actuallyIgnored = false,
                fileExists = true,
            ),
            WikiGitStateChecker.Issue(
                relativePath = ".clawdea/wiki-state.local.json",
                expected = WikiGitStateChecker.Expectation.IGNORED,
                actuallyTracked = true,
                actuallyIgnored = false,
                fileExists = true,
            ),
        ))

        assertTrue(captured.last().contains("2 issues"))
    }

    @Test
    fun `handleAction routes fix and dismiss to callbacks`() {
        val captured = mutableListOf<String>()
        val fix = IntArray(1)
        val dismiss = IntArray(1)
        val banner = newBanner(captured, fix, dismiss)

        banner.handleAction("fix")
        banner.handleAction("dismiss")

        assertEquals(1, fix[0])
        assertEquals(1, dismiss[0])
    }

    @Test
    fun `tooltip escapes summary text`() {
        val captured = mutableListOf<String>()
        val banner = newBanner(captured)

        banner.setIssues(listOf(
            WikiGitStateChecker.Issue(
                relativePath = "x/<a>.json",
                expected = WikiGitStateChecker.Expectation.TRACKED,
                actuallyTracked = false,
                actuallyIgnored = true,
                fileExists = true,
            ),
        ))

        val html = captured.last()
        assertFalse(html.contains("title=\"x/<a>.json"))
        assertTrue(html.contains("&lt;a&gt;"))
    }
}
