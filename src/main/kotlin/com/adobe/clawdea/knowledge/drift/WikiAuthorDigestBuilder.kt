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

import java.nio.file.Path

/**
 * Renders a list of drift events as the prompt body the wiki-author subagent
 * receives. Used by both the `/refresh-wiki` manual path and the auto-apply
 * background path; sharing the format keeps the subagent's contract uniform.
 */
object WikiAuthorDigestBuilder {

    /** The logical prefix the wiki-librarian records every suggestion path under. */
    private const val LOGICAL_WIKI_PREFIX = ".claude/wiki/"

    /**
     * @param wikiDir the actual wiki directory ([WikiLocator.wikiDir]). When
     *   provided, librarian-recorded `.claude/wiki/...` suggestion paths are
     *   rewritten to absolute paths under it. This is required in team mode,
     *   where the wiki lives somewhere else (e.g. `docs/llm-wiki/`): without the
     *   rewrite the author would create the page at the non-existent
     *   `<projectRoot>/.claude/wiki/...` instead. CodeRename/CommitDrift events
     *   already carry absolute wiki paths, so they are unaffected.
     */
    fun build(events: List<DriftEvent>, wikiDir: Path? = null): String {
        val sb = StringBuilder()
        sb.append("@wiki-author Acting on these drift events. Process them in order.\n\n")
        for (event in events) {
            renderEvent(sb, event, wikiDir)
            sb.append('\n')
        }
        sb.append("After acting, summarise which events you handled and which you skipped (with reason).")
        return sb.toString()
    }

    private fun resolveWikiPath(logical: String, wikiDir: Path?): String =
        if (wikiDir != null && logical.startsWith(LOGICAL_WIKI_PREFIX)) {
            wikiDir.resolve(logical.removePrefix(LOGICAL_WIKI_PREFIX)).toString()
        } else {
            logical
        }

    private fun renderEvent(sb: StringBuilder, event: DriftEvent, wikiDir: Path?) {
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
            is DriftEvent.OrphanSubsystem -> {
                sb.append("- $icon OrphanSubsystem: the `${event.prefix}*` classes have no wiki page\n")
                sb.append("  classes: ${event.classNames.joinToString(", ")}\n")
                sb.append("  representative files: ${event.representativePaths.joinToString(", ")}\n")
                sb.append("  action: if these form a coherent subsystem, author a new concept page and link it from index.md; otherwise skip with a reason\n")
            }
            is DriftEvent.WikiSuggestion -> {
                sb.append("- $icon WikiSuggestion (${event.kind.name}): ${event.title}\n")
                sb.append("  rationale: ${event.rationale}\n")
                val targets = event.targetFiles.joinToString(", ") { resolveWikiPath(it, wikiDir) }
                sb.append("  target files: $targets\n")
                if (event.sourcePage != null) {
                    sb.append("  observed while reading: ${resolveWikiPath(event.sourcePage, wikiDir)}\n")
                }
            }
        }
    }
}
