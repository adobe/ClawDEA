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
abstract class DriftEvent {
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

    data class DreamIndexCleanup(
        val targetFile: Path,
        val title: String,
        val patchPlan: String,
        val autoApplicable: Boolean,
    ) : DriftEvent() {
        override val signature: String = "dream-index-cleanup:$targetFile:$title"
    }

    data class DreamLinkNormalization(
        val targetFile: Path,
        val title: String,
        val patchPlan: String,
        val autoApplicable: Boolean,
    ) : DriftEvent() {
        override val signature: String = "dream-link-normalization:$targetFile:$title"
    }

    data class DreamSourceReferenceFix(
        val targetFile: Path,
        val title: String,
        val patchPlan: String,
        val autoApplicable: Boolean,
    ) : DriftEvent() {
        override val signature: String = "dream-source-ref-fix:$targetFile:$title"
    }

    data class DreamDuplicateConcept(
        val targetFile: Path,
        val title: String,
        val patchPlan: String,
    ) : DriftEvent() {
        override val signature: String = "dream-duplicate-concept:$targetFile:$title"
    }

    data class DreamStaleConcept(
        val targetFile: Path,
        val title: String,
        val patchPlan: String,
    ) : DriftEvent() {
        override val signature: String = "dream-stale-concept:$targetFile:$title"
    }

    data class DreamMissingConcept(
        val targetFile: Path,
        val title: String,
        val patchPlan: String,
    ) : DriftEvent() {
        override val signature: String = "dream-missing-concept:$targetFile:$title"
    }
}
