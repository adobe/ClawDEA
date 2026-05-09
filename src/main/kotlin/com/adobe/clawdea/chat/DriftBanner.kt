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

import com.adobe.clawdea.knowledge.drift.DriftEvent
import com.intellij.openapi.diagnostic.Logger

/**
 * Renders a top-of-chat banner showing pending drift events.
 *
 * Hidden when there are no events. The banner has two clickable actions:
 *   - `/refresh-wiki to review` → invokes [onInsertCommand] with `/refresh-wiki`
 *     so the caller can prefill the input area (no auto-send).
 *   - `dismiss` → invokes [onDismissAll] to dismiss every current event.
 *
 * Auto-applied fixes are reported as inline chat messages by the caller via
 * [appendAutoApplyNotification], NOT as banner content. The banner only ever
 * shows pending (un-applied, un-dismissed) drift events.
 */
class DriftBanner(
    private val updateHtml: (html: String) -> Unit,
    private val onInsertCommand: (cmd: String) -> Unit,
    private val onDismissAll: () -> Unit,
) {
    private var current: List<DriftEvent> = emptyList()

    fun setEvents(events: List<DriftEvent>) {
        current = events
        render()
    }

    /**
     * Build one human-readable line per auto-applied fix. Caller is responsible
     * for rendering each line as its own info-block / chat message so newlines
     * survive HTML escaping. Empty when [applied] is empty.
     */
    fun autoApplyNotificationLines(applied: List<DriftEvent>): List<String> {
        if (applied.isEmpty()) return emptyList()
        return applied.map { event ->
            when (event) {
                is DriftEvent.CodeRename -> {
                    val replacement = event.suggestedReplacement ?: "(removed)"
                    "✓ updated wiki ref: ${event.wikiPage.fileName} · ${event.brokenLink} → $replacement"
                }
                is DriftEvent.ManifestStale -> {
                    "✓ commented out stale manifest entry: ${event.repoKey} (path missing)"
                }
                is DriftEvent.DreamLinkNormalization -> {
                    "✓ normalized wiki link: ${event.targetFile.fileName} · ${event.title}"
                }
                is DriftEvent.DreamIndexCleanup -> {
                    "✓ applied Dream index cleanup: ${event.targetFile.fileName} · ${event.title}"
                }
                is DriftEvent.DreamSourceReferenceFix -> {
                    "✓ applied Dream source reference fix: ${event.targetFile.fileName} · ${event.title}"
                }
                is DriftEvent.DreamDuplicateConcept -> {
                    "✓ applied Dream duplicate concept update: ${event.targetFile.fileName} · ${event.title}"
                }
                is DriftEvent.DreamStaleConcept -> {
                    "✓ applied Dream stale concept update: ${event.targetFile.fileName} · ${event.title}"
                }
                is DriftEvent.DreamMissingConcept -> {
                    "✓ applied Dream missing concept update: ${event.targetFile.fileName} · ${event.title}"
                }
            }
        }
    }

    /** JS-side click dispatcher hook. Invoked from the JBCefJSQuery handler. */
    fun handleAction(action: String) {
        when (action) {
            "refresh" -> onInsertCommand("/refresh-wiki")
            "dismiss" -> onDismissAll()
            else -> LOG.warn("Unknown DriftBanner action: $action")
        }
    }

    private fun render() {
        if (current.isEmpty()) {
            updateHtml("<div id=\"drift-banner\" style=\"display:none;\"></div>")
            return
        }
        val n = current.size
        val plural = if (n == 1) "ref" else "refs"
        val html = """
            <div id="drift-banner" class="drift-banner">
                <span class="drift-banner-icon">⚠</span>
                <span class="drift-banner-text">wiki has $n stale $plural</span>
                <span class="drift-banner-action" data-action="drift-action" data-drift-action="refresh">/refresh-wiki to review</span>
                <span class="drift-banner-sep">·</span>
                <span class="drift-banner-action" data-action="drift-action" data-drift-action="dismiss">dismiss</span>
            </div>
        """.trimIndent()
        updateHtml(html)
    }

    companion object {
        private val LOG = Logger.getInstance(DriftBanner::class.java)
    }
}
