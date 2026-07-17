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
 * A drift event flagged by one of the detectors. Each event has a stable
 * `signature` used for dedup against the dismissed list and (for auto-apply)
 * to record successful fixes.
 */
sealed class DriftEvent {
    abstract val signature: String

    data class CodeRename(
        val wikiPage: Path,
        val brokenLink: String,
        val suggestedReplacement: String?,
    ) : DriftEvent() {
        override val signature: String = "code-rename:$wikiPage:$brokenLink"
    }

    data class ManifestStale(
        val repoKey: String,
        val groupName: String,
        val manifestPath: Path,
        val lineHint: Int,
    ) : DriftEvent() {
        override val signature: String = "manifest-stale:$manifestPath:$repoKey"
    }

    /**
     * Emitted by [CommitWikiDriftDetector] when commits since `lastScanAt`
     * touched files that a wiki concept page mentions. Whether the page is
     * actually stale is decided by the wiki-author subagent.
     */
    data class CommitDrift(
        val wikiPage: Path,
        val commitShas: List<String>,
        val touchedPaths: List<String>,
        val firstObservedAt: String,
    ) : DriftEvent() {
        override val signature: String =
            "commit-drift:${wikiPage.fileName}:${commitShas.joinToString(",").take(80)}"
    }

    /**
     * Emitted by [OrphanCodeDetector] when a cluster of source classes sharing
     * a common name prefix (e.g. all the `Codex*` types) exists in the codebase
     * but no wiki page mentions any of them — a greenfield subsystem that landed
     * without a concept page. Unlike [CommitDrift], which can only fire when a
     * commit touches code an *existing* page already references, this detector
     * finds areas the wiki has never covered. The wiki-author subagent decides
     * whether to author a new page.
     */
    data class OrphanSubsystem(
        val prefix: String,
        val classNames: List<String>,
        val representativePaths: List<String>,
    ) : DriftEvent() {
        override val signature: String = "orphan-subsystem:$prefix"
    }

    data class WikiSuggestion(
        val kind: SuggestionKind,
        val title: String,
        val rationale: String,
        val targetFiles: List<String>,
        val sourcePage: String?,
        val recordedAt: String,
    ) : DriftEvent() {
        override val signature: String =
            "wiki-suggestion:${kind.name}:${primaryTarget(targetFiles)}"

        companion object {
            internal fun primaryTarget(targetFiles: List<String>): String {
                val nonIndex = targetFiles.firstOrNull { !it.endsWith("index.md") }
                return nonIndex ?: targetFiles.firstOrNull() ?: ""
            }
        }
    }
}

enum class SuggestionKind { missingConcept, staleConcept, incompleteConcept }
