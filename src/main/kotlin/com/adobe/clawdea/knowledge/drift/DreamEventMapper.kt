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

import java.security.MessageDigest
import java.nio.file.Path

object DreamEventMapper {

    fun toEvent(projectRoot: Path, candidate: DreamCandidate): DriftEvent {
        val targetFile = projectRoot.resolve(candidate.targetFiles.first()).normalize()
        val autoApplicable = isAutoApplicable(candidate)
        val identity = evidenceIdentity(candidate)

        return when (candidate.kind) {
            DreamCandidateKind.INDEX_CLEANUP -> DriftEvent.DreamIndexCleanup(
                targetFile = targetFile,
                title = candidate.title,
                patchPlan = candidate.patchPlan,
                autoApplicable = false,
                identity = identity,
            )
            DreamCandidateKind.LINK_NORMALIZATION -> DriftEvent.DreamLinkNormalization(
                targetFile = targetFile,
                title = candidate.title,
                patchPlan = candidate.patchPlan,
                autoApplicable = autoApplicable,
                identity = identity,
            )
            DreamCandidateKind.SOURCE_REFERENCE_FIX -> DriftEvent.DreamSourceReferenceFix(
                targetFile = targetFile,
                title = candidate.title,
                patchPlan = candidate.patchPlan,
                autoApplicable = false,
                identity = identity,
            )
            DreamCandidateKind.DUPLICATE_CONCEPT -> DriftEvent.DreamDuplicateConcept(
                targetFile = targetFile,
                title = candidate.title,
                patchPlan = candidate.patchPlan,
                identity = identity,
            )
            DreamCandidateKind.STALE_CONCEPT -> DriftEvent.DreamStaleConcept(
                targetFile = targetFile,
                title = candidate.title,
                patchPlan = candidate.patchPlan,
                identity = identity,
            )
            DreamCandidateKind.MISSING_CONCEPT -> DriftEvent.DreamMissingConcept(
                targetFile = targetFile,
                title = candidate.title,
                patchPlan = candidate.patchPlan,
                identity = identity,
            )
        }
    }

    private fun evidenceIdentity(candidate: DreamCandidate): String {
        val canonicalEvidence = candidate.evidence
            .map { "${it.type.name.lowercase()}:${normalizeEvidenceRef(it.ref)}" }
            .sorted()
            .joinToString("|")
        return sha256(canonicalEvidence).take(12)
    }

    private fun normalizeEvidenceRef(ref: String): String =
        ref.trim().replace('\\', '/')

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun isAutoApplicable(candidate: DreamCandidate): Boolean {
        val targetFile = candidate.targetFiles.singleOrNull() ?: return false
        return candidate.kind == DreamCandidateKind.LINK_NORMALIZATION &&
            candidate.proposedAction == DreamProposedAction.APPLY_LOW_RISK &&
            candidate.confidence == DreamConfidence.HIGH &&
            candidate.contextCost != DreamContextCost.ADDS_CONTEXT &&
            isSafeWikiMarkdownTarget(targetFile)
    }

    private fun isSafeWikiMarkdownTarget(path: String): Boolean {
        if (!path.startsWith(".claude/wiki/")) return false
        if (path == ".claude/wiki/") return false
        if (!path.endsWith(".md")) return false
        if (path.contains("..") || path.contains("\\")) return false
        if (path.startsWith("/") || path.startsWith("\\")) return false
        return !WINDOWS_ABSOLUTE_PATH_RX.matches(path)
    }

    private val WINDOWS_ABSOLUTE_PATH_RX = Regex("""^[A-Za-z]:[\\/].*""")
}
