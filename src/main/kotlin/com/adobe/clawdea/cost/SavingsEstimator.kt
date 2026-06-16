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
package com.adobe.clawdea.cost

/**
 * Pure estimation engine for the ClawDEA-vs-standard-Claude-Code savings model. Every figure
 * is anchored in real observed tokens; only the counterfactual ("what standard CC would have
 * spent") is modeled, and only those modeled pieces carry band width. All methods are pure.
 *
 * Sign convention: positive = saving, negative = cost.
 */
object SavingsEstimator {

    /** High end of the grep counterfactual: index tool also avoids a full-tree scan pass. */
    private const val GREP_SCAN_FACTOR = 1.5

    /**
     * Lever 1 — wiki-librarian / subagent routing. Net = estimated avoided inline cost minus the
     * subagent's real measured cost. CAN BE NEGATIVE (one-shot question: subagent burned tokens,
     * nothing compounding was avoided). The avoided counterfactual = inline read tokens that would
     * have ridden the main context for (1 + remainingTurns) turns.
     */
    fun librarian(obs: TurnObservation): SavingsComponent {
        if (obs.subagents.isEmpty()) return SavingsComponent(LeverId.LIBRARIAN, SavingsBand.ZERO, measured = false)
        val perInputToken = ModelPricing.rateFor(obs.model).inputPerM / 1_000_000.0
        var band = SavingsBand.ZERO
        for (s in obs.subagents) {
            val rideTurns = 1 + obs.remainingTurns.coerceAtLeast(0)
            val inlineTokens = if (s.filesReadTokens > 0) s.filesReadTokens else s.inputTokens
            val cacheRead = perInputToken * ModelPricing.CACHE_READ_MULTIPLIER
            val cacheCreate = perInputToken * ModelPricing.CACHE_CREATION_MULTIPLIER
            val avoidedLow = inlineTokens * cacheRead   // one turn only (no re-ride)
            val avoidedExpected = inlineTokens * cacheRead * rideTurns
            val avoidedHigh = inlineTokens * cacheCreate * rideTurns
            val net = SavingsBand(
                low = avoidedLow - s.costUsd,
                expected = avoidedExpected - s.costUsd,
                high = avoidedHigh - s.costUsd,
            )
            band += net
        }
        return SavingsComponent(LeverId.LIBRARIAN, band, measured = false)
    }

    /**
     * Lever 2 — IDE index tools replacing grep + reads. Avoided = the token size of the hit files
     * the tool surfaced (low = 1 file's worth, expected = all hits, high = all hits + a grep scan
     * pass). Priced at cache-read rate (those reads would have ridden the main context).
     */
    fun indexTools(obs: TurnObservation): SavingsComponent {
        if (obs.indexTools.isEmpty()) return SavingsComponent(LeverId.INDEX_TOOLS, SavingsBand.ZERO, measured = false)
        val cacheRead = (ModelPricing.rateFor(obs.model).inputPerM / 1_000_000.0) * ModelPricing.CACHE_READ_MULTIPLIER
        var band = SavingsBand.ZERO
        for (t in obs.indexTools) {
            val perHit = if (t.hitCount > 0) t.hitFilesTokens.toDouble() / t.hitCount else t.hitFilesTokens.toDouble()
            val low = perHit * cacheRead
            val expected = t.hitFilesTokens * cacheRead
            val high = t.hitFilesTokens * cacheRead * GREP_SCAN_FACTOR
            band += SavingsBand(low, expected, high)
        }
        return SavingsComponent(LeverId.INDEX_TOOLS, band, measured = false)
    }

    /**
     * Lever 4 — primer (wiki TOC + REPO_STATE) per-turn overhead. A real cost: the extra tokens
     * ClawDEA ships every turn that standard CC would not. Measured exactly from the cache-read
     * vs cache-creation split, priced via the same multipliers. Negative (a cost).
     */
    fun primerOverhead(obs: TurnObservation): SavingsComponent {
        val perToken = ModelPricing.rateFor(obs.model).inputPerM / 1_000_000.0
        val cost = obs.primerCacheReadTokens * perToken * ModelPricing.CACHE_READ_MULTIPLIER +
            obs.primerCacheCreationTokens * perToken * ModelPricing.CACHE_CREATION_MULTIPLIER
        return SavingsComponent(LeverId.PRIMER_OVERHEAD, SavingsBand.exact(-cost), measured = true)
    }

    /** Lever 3 — knowledge upkeep already measured in dollars; a pure cost, no band. */
    fun knowledgeUpkeep(obs: TurnObservation): SavingsComponent =
        SavingsComponent(LeverId.KNOWLEDGE_UPKEEP, SavingsBand.exact(-obs.knowledgeUpkeepUsd), measured = true)

    /** All four components for a turn, in display order. */
    fun components(obs: TurnObservation): List<SavingsComponent> =
        listOf(librarian(obs), indexTools(obs), knowledgeUpkeep(obs), primerOverhead(obs))

    /** Net band for a turn = sum of all component bands. */
    fun aggregate(obs: TurnObservation): SavingsBand =
        components(obs).fold(SavingsBand.ZERO) { acc, c -> acc + c.band }

    /**
     * Confidence from band width relative to magnitude. Wide band or near-zero magnitude → ROUGH.
     * Threshold: relative width <= 0.5 → ESTIMATE, else ROUGH.
     */
    fun confidence(band: SavingsBand): Confidence {
        val mag = kotlin.math.abs(band.expected)
        if (mag < 1e-6) return Confidence.ROUGH
        val relWidth = kotlin.math.abs(band.high - band.low) / mag
        return if (relWidth <= 0.5) Confidence.ESTIMATE else Confidence.ROUGH
    }
}
