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

    data class DreamIndexCleanup(
        val targetFile: Path,
        val title: String,
        val patchPlan: String,
        val autoApplicable: Boolean,
        val identity: String = "",
    ) : DriftEvent() {
        override val signature: String = dreamSignature("dream-index-cleanup", targetFile, identity)
    }

    data class DreamLinkNormalization(
        val targetFile: Path,
        val title: String,
        val patchPlan: String,
        val autoApplicable: Boolean,
        val identity: String = "",
    ) : DriftEvent() {
        override val signature: String = dreamSignature("dream-link-normalization", targetFile, identity)
    }

    data class DreamSourceReferenceFix(
        val targetFile: Path,
        val title: String,
        val patchPlan: String,
        val autoApplicable: Boolean,
        val identity: String = "",
    ) : DriftEvent() {
        override val signature: String = dreamSignature("dream-source-ref-fix", targetFile, identity)
    }

    data class DreamDuplicateConcept(
        val targetFile: Path,
        val title: String,
        val patchPlan: String,
        val identity: String = "",
    ) : DriftEvent() {
        override val signature: String = dreamSignature("dream-duplicate-concept", targetFile, identity)
    }

    data class DreamStaleConcept(
        val targetFile: Path,
        val title: String,
        val patchPlan: String,
        val identity: String = "",
    ) : DriftEvent() {
        override val signature: String = dreamSignature("dream-stale-concept", targetFile, identity)
    }

    data class DreamMissingConcept(
        val targetFile: Path,
        val title: String,
        val patchPlan: String,
        val identity: String = "",
    ) : DriftEvent() {
        override val signature: String = dreamSignature("dream-missing-concept", targetFile, identity)
    }
}

private fun dreamSignature(prefix: String, targetFile: Path, identity: String): String {
    val target = dreamSignatureTarget(targetFile)
    return if (identity.isBlank()) "$prefix:$target" else "$prefix:$target:$identity"
}

private fun dreamSignatureTarget(targetFile: Path): String {
    val normalized = targetFile.normalize()
    val names = (0 until normalized.nameCount).map { normalized.getName(it).toString() }
    val wikiIndex = names.windowed(size = 2).indexOfFirst { it == listOf(".claude", "wiki") }
    val signatureNames = if (wikiIndex >= 0) {
        names.drop(wikiIndex + 2)
    } else {
        names
    }
    return signatureNames.joinToString("/").ifEmpty { normalized.fileName.toString() }
}
