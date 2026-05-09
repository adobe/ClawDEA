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
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamOutputValidatorTest {

    @Test fun `accepts candidate with supported kind and evidence`() {
        val result = DreamOutputValidator.validate(VALID_JSON)

        assertEquals(emptyList<String>(), result.errors)
        assertEquals(1, result.candidates.size)
        val candidate = result.candidates.single()
        assertEquals(DreamCandidateKind.LINK_NORMALIZATION, candidate.kind)
        assertEquals("Normalize rollout link", candidate.title)
        assertEquals(listOf(".claude/wiki/index.md"), candidate.targetFiles)
        assertEquals(DreamEvidenceType.STALE_LINK, candidate.evidence.single().type)
        assertEquals(DreamContextCost.NEUTRAL, candidate.contextCost)
        assertEquals(DreamConfidence.HIGH, candidate.confidence)
        assertEquals(DreamProposedAction.APPLY_LOW_RISK, candidate.proposedAction)
    }

    @Test fun `rejects candidate without evidence`() {
        val result = DreamOutputValidator.validate("""
            {
              "candidates": [
                {
                  "kind": "linkNormalization",
                  "title": "Normalize rollout link",
                  "targetFiles": [".claude/wiki/index.md"],
                  "evidence": [],
                  "usefulness": "Keeps wiki references parseable.",
                  "contextCost": "neutral",
                  "confidence": "high",
                  "proposedAction": "applyLowRisk",
                  "patchPlan": "Replace one old wikilink."
                }
              ]
            }
        """.trimIndent())

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("evidence") })
    }

    @Test fun `rejects malformed JSON`() {
        val result = DreamOutputValidator.validate("""{"candidates": [""")

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("Malformed JSON") })
    }

    @Test fun `mixed valid and invalid batch returns no candidates`() {
        val result = DreamOutputValidator.validate("""
            {
              "candidates": [
                ${candidateJson()},
                ${candidateJson(evidence = "[]")}
              ]
            }
        """.trimIndent())

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("evidence") })
    }

    @Test fun `absolute target file is rejected`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            targetFiles = """["/Users/me/project/.claude/wiki/index.md"]""",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("targetFiles") })
    }

    @Test fun `target file outside wiki is rejected`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            targetFiles = """["src/main/kotlin/Foo.kt"]""",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("targetFiles") })
    }

    @Test fun `missing concept with apply low risk is rejected`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            kind = "missingConcept",
            contextCost = "neutral",
            confidence = "high",
            proposedAction = "applyLowRisk",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("applyLowRisk") })
    }

    @Test fun `unknown semantic field is rejected`() {
        val result = DreamOutputValidator.validate(jsonWithCandidate(candidateJson(
            extraFields = ""","deleteFiles":[".claude/wiki/index.md"]""",
        )))

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("deleteFiles") })
    }

    @Test fun `unknown root semantic field is rejected`() {
        val result = DreamOutputValidator.validate("""
            {
              "candidates": [
                ${candidateJson()}
              ],
              "applyNow": true
            }
        """.trimIndent())

        assertEquals(emptyList<DreamCandidate>(), result.candidates)
        assertTrue(result.errors.any { it.contains("applyNow") })
    }

    private companion object {
        val VALID_JSON = """
            {
              "candidates": [
                {
                  "kind": "linkNormalization",
                  "title": "Normalize rollout link",
                  "targetFiles": [".claude/wiki/index.md"],
                  "evidence": [
                    {
                      "type": "staleLink",
                      "ref": ".claude/wiki/index.md",
                      "summary": "old wikilink"
                    }
                  ],
                  "usefulness": "Keeps wiki references parseable.",
                  "contextCost": "neutral",
                  "confidence": "high",
                  "proposedAction": "applyLowRisk",
                  "patchPlan": "Replace one old wikilink."
                }
              ]
            }
        """.trimIndent()

        fun jsonWithCandidate(candidate: String): String = """
            {
              "candidates": [
                $candidate
              ]
            }
        """.trimIndent()

        fun candidateJson(
            kind: String = "linkNormalization",
            targetFiles: String = """[".claude/wiki/index.md"]""",
            evidence: String = """
                [
                  {
                    "type": "staleLink",
                    "ref": ".claude/wiki/index.md",
                    "summary": "old wikilink"
                  }
                ]
            """.trimIndent(),
            contextCost: String = "neutral",
            confidence: String = "high",
            proposedAction: String = "applyLowRisk",
            extraFields: String = "",
        ): String = """
            {
              "kind": "$kind",
              "title": "Normalize rollout link",
              "targetFiles": $targetFiles,
              "evidence": $evidence,
              "usefulness": "Keeps wiki references parseable.",
              "contextCost": "$contextCost",
              "confidence": "$confidence",
              "proposedAction": "$proposedAction",
              "patchPlan": "Replace one old wikilink."$extraFields
            }
        """.trimIndent()
    }
}
