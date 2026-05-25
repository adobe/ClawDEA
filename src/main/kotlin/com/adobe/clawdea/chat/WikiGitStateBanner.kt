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
import com.intellij.openapi.diagnostic.Logger

/**
 * Top-of-chat banner that surfaces wiki-state files whose git tracking status
 * doesn't match the team-mode contract (see [WikiGitStateChecker]). Sits above
 * the [DriftBanner] so two issues don't fight for the same DOM node.
 *
 * The banner has two clickable actions:
 *   - `fix it` → invokes [onFix] (apply corrective git ops, then re-render).
 *   - `dismiss` → invokes [onDismiss] (hide for the rest of the session).
 */
class WikiGitStateBanner(
    private val updateHtml: (html: String) -> Unit,
    private val onFix: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private var current: List<WikiGitStateChecker.Issue> = emptyList()

    fun setIssues(issues: List<WikiGitStateChecker.Issue>) {
        current = issues
        render()
    }

    fun handleAction(action: String) {
        when (action) {
            "fix" -> onFix()
            "dismiss" -> onDismiss()
            else -> LOG.warn("Unknown WikiGitStateBanner action: $action")
        }
    }

    private fun render() {
        if (current.isEmpty()) {
            updateHtml("""<div id="wiki-git-state-banner" style="display:none;"></div>""")
            return
        }
        val n = current.size
        val label = if (n == 1) "1 issue" else "$n issues"
        val tooltip = current.joinToString("\n") { it.summary() }
        val safeTooltip = escapeHtml(tooltip)
        val html = """
            <div id="wiki-git-state-banner" class="wiki-git-state-banner" title="$safeTooltip">
                <span class="wiki-git-state-banner-icon">⚠</span>
                <span class="wiki-git-state-banner-text">wiki state files: $label with unexpected git status</span>
                <span class="wiki-git-state-banner-action" data-action="wiki-git-state-action" data-wgs-action="fix">fix it</span>
                <span class="wiki-git-state-banner-sep">·</span>
                <span class="wiki-git-state-banner-action" data-action="wiki-git-state-action" data-wgs-action="dismiss">dismiss</span>
            </div>
        """.trimIndent()
        updateHtml(html)
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("\n", "&#10;")

    companion object {
        private val LOG = Logger.getInstance(WikiGitStateBanner::class.java)
    }
}
