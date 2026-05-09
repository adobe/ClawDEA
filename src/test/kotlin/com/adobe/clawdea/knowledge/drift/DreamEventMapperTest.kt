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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class DreamEventMapperTest {

    @Test fun `maps linkNormalization applyLowRisk to auto-applicable event`() {
        val projectRoot = Path.of("/repo")
        val candidate = candidate(
            kind = DreamCandidateKind.LINK_NORMALIZATION,
            title = "Normalize rollout link",
            targetFiles = listOf(".claude/wiki/index.md"),
            contextCost = DreamContextCost.NEUTRAL,
            confidence = DreamConfidence.HIGH,
            proposedAction = DreamProposedAction.APPLY_LOW_RISK,
        )

        val event = DreamEventMapper.toEvent(projectRoot, candidate)

        assertTrue(event is DriftEvent.DreamLinkNormalization)
        event as DriftEvent.DreamLinkNormalization
        assertEquals(projectRoot.resolve(".claude/wiki/index.md").normalize(), event.targetFile)
        assertEquals("Normalize rollout link", event.title)
        assertEquals("Replace one old wikilink.", event.patchPlan)
        assertTrue(event.autoApplicable)
        assertTrue(event.signature.startsWith("dream-link-normalization:index.md:"))
        assertFalse(event.signature.contains("/repo"))
        assertFalse(event.signature.contains("Normalize rollout link"))
    }

    @Test fun `maps missingConcept to missing concept event`() {
        val projectRoot = Path.of("/repo")
        val candidate = candidate(
            kind = DreamCandidateKind.MISSING_CONCEPT,
            title = "Add rollout concept",
            targetFiles = listOf(".claude/wiki/concepts/rollout-flow.md"),
            contextCost = DreamContextCost.ADDS_CONTEXT,
            confidence = DreamConfidence.MEDIUM,
            proposedAction = DreamProposedAction.PROPOSE_DIFF,
        )

        val event = DreamEventMapper.toEvent(projectRoot, candidate)

        assertTrue(event is DriftEvent.DreamMissingConcept)
        event as DriftEvent.DreamMissingConcept
        assertEquals(projectRoot.resolve(".claude/wiki/concepts/rollout-flow.md").normalize(), event.targetFile)
        assertEquals("Add rollout concept", event.title)
        assertTrue(event.signature.startsWith("dream-missing-concept:concepts/rollout-flow.md:"))
        assertFalse(event.signature.contains("/repo"))
        assertFalse(event.signature.contains("Add rollout concept"))
    }

    @Test fun `indexCleanup with applyLowRisk is not autoApplicable`() {
        val event = DreamEventMapper.toEvent(
            Path.of("/repo"),
            candidate(
                kind = DreamCandidateKind.INDEX_CLEANUP,
                title = "Tighten index",
                targetFiles = listOf(".claude/wiki/index.md"),
                contextCost = DreamContextCost.SHRINKS_CONTEXT,
                confidence = DreamConfidence.HIGH,
                proposedAction = DreamProposedAction.APPLY_LOW_RISK,
            ),
        )

        assertTrue(event is DriftEvent.DreamIndexCleanup)
        event as DriftEvent.DreamIndexCleanup
        assertFalse(event.autoApplicable)
        assertTrue(event.signature.startsWith("dream-index-cleanup:index.md:"))
        assertFalse(event.signature.contains("/repo"))
        assertFalse(event.signature.contains("Tighten index"))
    }

    @Test fun `same target linkNormalization candidates with different evidence refs have different signatures`() {
        val projectRoot = Path.of("/repo")
        val first = DreamEventMapper.toEvent(
            projectRoot,
            candidate(
                kind = DreamCandidateKind.LINK_NORMALIZATION,
                title = "Normalize rollout link",
                targetFiles = listOf(".claude/wiki/index.md"),
                contextCost = DreamContextCost.NEUTRAL,
                confidence = DreamConfidence.HIGH,
                proposedAction = DreamProposedAction.APPLY_LOW_RISK,
                evidence = listOf(DreamEvidence(DreamEvidenceType.STALE_LINK, ".claude/wiki/index.md#rollout", "old wikilink")),
            ),
        )
        val second = DreamEventMapper.toEvent(
            projectRoot,
            candidate(
                kind = DreamCandidateKind.LINK_NORMALIZATION,
                title = "Normalize rollout link",
                targetFiles = listOf(".claude/wiki/index.md"),
                contextCost = DreamContextCost.NEUTRAL,
                confidence = DreamConfidence.HIGH,
                proposedAction = DreamProposedAction.APPLY_LOW_RISK,
                evidence = listOf(DreamEvidence(DreamEvidenceType.STALE_LINK, ".claude/wiki/index.md#authoring", "old wikilink")),
            ),
        )

        assertFalse(first.signature == second.signature)
        assertTrue(first.signature.startsWith("dream-link-normalization:index.md:"))
        assertTrue(second.signature.startsWith("dream-link-normalization:index.md:"))
    }

    private fun candidate(
        kind: DreamCandidateKind,
        title: String,
        targetFiles: List<String>,
        contextCost: DreamContextCost,
        confidence: DreamConfidence,
        proposedAction: DreamProposedAction,
        evidence: List<DreamEvidence> = listOf(DreamEvidence(DreamEvidenceType.STALE_LINK, targetFiles.first(), "old wikilink")),
    ): DreamCandidate = DreamCandidate(
        kind = kind,
        title = title,
        targetFiles = targetFiles,
        evidence = evidence,
        usefulness = "Keeps wiki references parseable.",
        contextCost = contextCost,
        confidence = confidence,
        proposedAction = proposedAction,
        patchPlan = "Replace one old wikilink.",
    )
}
