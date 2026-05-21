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
package com.adobe.clawdea.knowledge.drift

/**
 * Renders a list of drift events as the prompt body the wiki-author subagent
 * receives. Used by both the `/refresh-wiki` manual path and the auto-apply
 * background path; sharing the format keeps the subagent's contract uniform.
 */
object WikiAuthorDigestBuilder {

    fun build(events: List<DriftEvent>): String {
        val sb = StringBuilder()
        sb.append("@wiki-author Acting on these drift events. Process them in order.\n\n")
        for (event in events) {
            renderEvent(sb, event)
            sb.append('\n')
        }
        sb.append("After acting, summarise which events you handled and which you skipped (with reason).")
        return sb.toString()
    }

    private fun renderEvent(sb: StringBuilder, event: DriftEvent) {
        val icon = DriftEventIcon.iconFor(event)
        when (event) {
            is DriftEvent.CommitDrift -> {
                sb.append("- $icon CommitDrift on ${event.wikiPage}\n")
                sb.append("  commits: ${event.commitShas.joinToString(", ")}\n")
                sb.append("  touched paths that this page mentions: ${event.touchedPaths.joinToString(", ")}\n")
                sb.append("  first observed at: ${event.firstObservedAt}\n")
            }
            is DriftEvent.CodeRename -> {
                sb.append("- $icon CodeRename in ${event.wikiPage}\n")
                sb.append("  broken link: ${event.brokenLink}\n")
                if (event.suggestedReplacement != null) {
                    sb.append("  suggested replacement: ${event.suggestedReplacement}\n")
                }
            }
            is DriftEvent.ManifestStale -> {
                sb.append("- $icon ManifestStale in ${event.manifestPath}\n")
                sb.append("  repo key: ${event.repoKey} (group ${event.groupName}, line ${event.lineHint})\n")
            }
            is DriftEvent.WikiSuggestion -> {
                sb.append("- $icon WikiSuggestion (${event.kind.name}): ${event.title}\n")
                sb.append("  rationale: ${event.rationale}\n")
                sb.append("  target files: ${event.targetFiles.joinToString(", ")}\n")
                if (event.sourcePage != null) {
                    sb.append("  observed while reading: ${event.sourcePage}\n")
                }
            }
        }
    }
}
