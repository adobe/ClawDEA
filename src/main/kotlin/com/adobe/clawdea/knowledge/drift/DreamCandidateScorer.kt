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

object DreamCandidateScorer {

    private val KIND_PRIORITY = listOf(
        DreamCandidateKind.INDEX_CLEANUP,
        DreamCandidateKind.SOURCE_REFERENCE_FIX,
        DreamCandidateKind.LINK_NORMALIZATION,
        DreamCandidateKind.DUPLICATE_CONCEPT,
        DreamCandidateKind.STALE_CONCEPT,
        DreamCandidateKind.MISSING_CONCEPT,
    ).withIndex().associate { it.value to it.index }

    private val CONTEXT_COST_PRIORITY = listOf(
        DreamContextCost.SHRINKS_CONTEXT,
        DreamContextCost.NEUTRAL,
        DreamContextCost.ADDS_CONTEXT,
    ).withIndex().associate { it.value to it.index }

    private val CONFIDENCE_PRIORITY = listOf(
        DreamConfidence.HIGH,
        DreamConfidence.MEDIUM,
        DreamConfidence.LOW,
    ).withIndex().associate { it.value to it.index }

    fun filterAndRank(
        candidates: List<DreamCandidate>,
        maxCandidates: Int = 10,
        indexOverContextCap: Boolean = false,
    ): List<DreamCandidate> =
        candidates
            .asSequence()
            .filterNot { it.confidence == DreamConfidence.LOW }
            .filterNot { it.patchPlan.length > MAX_PATCH_PLAN_CHARS }
            .filterNot { indexOverContextCap && it.contextCost == DreamContextCost.ADDS_CONTEXT && it.targetsWikiIndex() }
            .filterNot { it.contextCost == DreamContextCost.ADDS_CONTEXT && it.evidence.size < 2 }
            .sortedWith(
                compareBy<DreamCandidate>(
                    { KIND_PRIORITY.getValue(it.kind) },
                    { CONTEXT_COST_PRIORITY.getValue(it.contextCost) },
                    { CONFIDENCE_PRIORITY.getValue(it.confidence) },
                    { if (it.hasUserCorrection()) 0 else 1 },
                ),
            )
            .take(maxCandidates)
            .toList()

    private fun DreamCandidate.targetsWikiIndex(): Boolean =
        targetFiles.any { it == ".claude/wiki/index.md" }

    private fun DreamCandidate.hasUserCorrection(): Boolean =
        evidence.any { it.type == DreamEvidenceType.USER_CORRECTION }

    private const val MAX_PATCH_PLAN_CHARS = 4_000
}
