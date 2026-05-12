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

import org.junit.Assert.assertEquals
import org.junit.Test

class DreamCandidateScorerTest {

    @Test fun `drops low confidence candidates`() {
        val kept = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            contextCost = DreamContextCost.NEUTRAL,
            confidence = DreamConfidence.MEDIUM,
        )
        val dropped = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            contextCost = DreamContextCost.NEUTRAL,
            confidence = DreamConfidence.LOW,
            evidence = listOf(evidence("one"), evidence("two")),
        )

        assertEquals(listOf(kept), DreamCandidateScorer.filterAndRank(listOf(dropped, kept)))
    }

    @Test fun `drops add-context candidates with fewer than 2 evidence entries`() {
        val kept = candidate(
            kind = DreamCandidateKind.MISSING_CONCEPT,
            contextCost = DreamContextCost.ADDS_CONTEXT,
            confidence = DreamConfidence.HIGH,
            evidence = listOf(evidence("one"), evidence("two")),
        )
        val dropped = candidate(
            kind = DreamCandidateKind.MISSING_CONCEPT,
            contextCost = DreamContextCost.ADDS_CONTEXT,
            confidence = DreamConfidence.HIGH,
            evidence = listOf(evidence("one")),
        )

        assertEquals(listOf(kept), DreamCandidateScorer.filterAndRank(listOf(dropped, kept)))
    }

    @Test fun `prefers index cleanup and shrinking context before new pages`() {
        val newPage = candidate(
            kind = DreamCandidateKind.MISSING_CONCEPT,
            contextCost = DreamContextCost.ADDS_CONTEXT,
            confidence = DreamConfidence.HIGH,
            title = "Create concept",
            evidence = listOf(evidence("one"), evidence("two")),
        )
        val linkFix = candidate(
            kind = DreamCandidateKind.LINK_NORMALIZATION,
            contextCost = DreamContextCost.NEUTRAL,
            confidence = DreamConfidence.HIGH,
            title = "Normalize link",
        )
        val cleanup = candidate(
            kind = DreamCandidateKind.INDEX_CLEANUP,
            contextCost = DreamContextCost.SHRINKS_CONTEXT,
            confidence = DreamConfidence.MEDIUM,
            title = "Clean index",
        )

        assertEquals(listOf(cleanup, linkFix, newPage), DreamCandidateScorer.filterAndRank(listOf(newPage, linkFix, cleanup)))
    }

    @Test fun `orders all candidate kinds by maintenance priority`() {
        val missingConcept = candidate(DreamCandidateKind.MISSING_CONCEPT)
        val staleConcept = candidate(DreamCandidateKind.STALE_CONCEPT)
        val duplicateConcept = candidate(DreamCandidateKind.DUPLICATE_CONCEPT)
        val linkNormalization = candidate(DreamCandidateKind.LINK_NORMALIZATION)
        val sourceReferenceFix = candidate(DreamCandidateKind.SOURCE_REFERENCE_FIX)
        val indexCleanup = candidate(DreamCandidateKind.INDEX_CLEANUP)

        assertEquals(
            listOf(indexCleanup, sourceReferenceFix, linkNormalization, duplicateConcept, staleConcept, missingConcept),
            DreamCandidateScorer.filterAndRank(listOf(
                missingConcept,
                staleConcept,
                duplicateConcept,
                linkNormalization,
                sourceReferenceFix,
                indexCleanup,
            )),
        )
    }

    @Test fun `orders context cost within otherwise equal candidates`() {
        val adds = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            contextCost = DreamContextCost.ADDS_CONTEXT,
            evidence = listOf(evidence("one"), evidence("two")),
        )
        val neutral = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            contextCost = DreamContextCost.NEUTRAL,
        )
        val shrinks = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            contextCost = DreamContextCost.SHRINKS_CONTEXT,
        )

        assertEquals(listOf(shrinks, neutral, adds), DreamCandidateScorer.filterAndRank(listOf(adds, neutral, shrinks)))
    }

    @Test fun `orders confidence within otherwise equal candidates`() {
        val medium = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            confidence = DreamConfidence.MEDIUM,
        )
        val high = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            confidence = DreamConfidence.HIGH,
        )

        assertEquals(listOf(high, medium), DreamCandidateScorer.filterAndRank(listOf(medium, high)))
    }

    @Test fun `caps ranked candidates to requested maximum`() {
        val third = candidate(
            kind = DreamCandidateKind.DUPLICATE_CONCEPT,
            confidence = DreamConfidence.MEDIUM,
        )
        val medium = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            confidence = DreamConfidence.MEDIUM,
        )
        val high = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            confidence = DreamConfidence.HIGH,
        )

        assertEquals(listOf(third, high), DreamCandidateScorer.filterAndRank(listOf(medium, third, high), maxCandidates = 2))
    }

    @Test fun `USER_CORRECTION evidence boosts candidate above peer with no such evidence`() {
        val withCorrection = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            contextCost = DreamContextCost.NEUTRAL,
            confidence = DreamConfidence.HIGH,
            title = "With correction",
            evidence = listOf(DreamEvidence(DreamEvidenceType.USER_CORRECTION, "ctx", "User fixed wrong assumption")),
        )
        val withoutCorrection = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            contextCost = DreamContextCost.NEUTRAL,
            confidence = DreamConfidence.HIGH,
            title = "Without correction",
            evidence = listOf(DreamEvidence(DreamEvidenceType.SESSION_SIGNAL, "ctx", "Generic signal")),
        )

        val ranked = DreamCandidateScorer.filterAndRank(listOf(withoutCorrection, withCorrection))
        assertEquals(listOf(withCorrection, withoutCorrection), ranked)
    }

    @Test fun `USER_CORRECTION evidence type is accepted by scorer`() {
        val withCorrection = candidate(
            kind = DreamCandidateKind.MISSING_CONCEPT,
            contextCost = DreamContextCost.ADDS_CONTEXT,
            confidence = DreamConfidence.HIGH,
            evidence = listOf(
                DreamEvidence(DreamEvidenceType.USER_CORRECTION, "ctx1", "User said policy is inert"),
                DreamEvidence(DreamEvidenceType.SESSION_SIGNAL, "ctx2", "Observed signal"),
            ),
        )
        val result = DreamCandidateScorer.filterAndRank(listOf(withCorrection))
        assertEquals(1, result.size)
        assertEquals(DreamEvidenceType.USER_CORRECTION, result[0].evidence[0].type)
    }

    @Test fun `drops oversized patch plans`() {
        val kept = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            confidence = DreamConfidence.MEDIUM,
        )
        val dropped = candidate(
            kind = DreamCandidateKind.STALE_CONCEPT,
            confidence = DreamConfidence.HIGH,
            patchPlan = "x".repeat(4_001),
        )

        assertEquals(listOf(kept), DreamCandidateScorer.filterAndRank(listOf(dropped, kept)))
    }

    private fun candidate(kind: DreamCandidateKind): DreamCandidate = candidate(
        kind = kind,
        contextCost = DreamContextCost.NEUTRAL,
        confidence = DreamConfidence.HIGH,
    )

    private fun candidate(
        kind: DreamCandidateKind,
        contextCost: DreamContextCost = DreamContextCost.NEUTRAL,
        confidence: DreamConfidence = DreamConfidence.HIGH,
        title: String = kind.name,
        evidence: List<DreamEvidence> = listOf(evidence("one")),
        patchPlan: String = "Report the candidate.",
    ): DreamCandidate = DreamCandidate(
        kind = kind,
        title = title,
        targetFiles = listOf(".claude/wiki/index.md"),
        evidence = evidence,
        usefulness = "Useful candidate.",
        contextCost = contextCost,
        confidence = confidence,
        proposedAction = DreamProposedAction.REPORT_ONLY,
        patchPlan = patchPlan,
    )

    private fun evidence(ref: String): DreamEvidence = DreamEvidence(
        type = DreamEvidenceType.SESSION_SIGNAL,
        ref = ref,
        summary = "Observed signal.",
    )
}
