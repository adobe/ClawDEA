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
     * Lever 1 — wiki-librarian / subagent routing. The honest counterfactual: had the main loop
     * explored inline, it would have paid the FIRST read of those tokens at full input rate (the
     * same as the subagent did) and then RE-RIDDEN them in the main context for the remaining turns
     * at cache-read rate. The saving is therefore the avoided re-rides, net of the subagent's own
     * real cost. CAN BE NEGATIVE: a one-shot question (remainingTurns 0) avoids no re-rides, and an
     * expensive subagent that read little nets negative — both honest outcomes.
     *
     * Band: low = no re-rides materialise (first read only); expected = re-rides at cache-read rate
     * for the remaining turns; high = re-rides at cache-creation rate (context kept being rewritten).
     */
    fun librarian(obs: TurnObservation): SavingsComponent {
        if (obs.subagents.isEmpty()) return SavingsComponent(LeverId.LIBRARIAN, SavingsBand.ZERO, measured = false)
        val perInputToken = ModelPricing.rateFor(obs.model).inputPerM / 1_000_000.0
        val cacheRead = perInputToken * ModelPricing.CACHE_READ_MULTIPLIER
        val cacheCreate = perInputToken * ModelPricing.CACHE_CREATION_MULTIPLIER
        val reRides = obs.remainingTurns.coerceAtLeast(0)
        var band = SavingsBand.ZERO
        for (s in obs.subagents) {
            val inlineTokens = if (s.filesReadTokens > 0) s.filesReadTokens else s.inputTokens
            val firstRead = inlineTokens * perInputToken
            val avoidedLow = firstRead
            val avoidedExpected = firstRead + inlineTokens * cacheRead * reRides
            val avoidedHigh = firstRead + inlineTokens * cacheCreate * reRides
            band += SavingsBand(
                low = avoidedLow - s.costUsd,
                expected = avoidedExpected - s.costUsd,
                high = avoidedHigh - s.costUsd,
            )
        }
        return SavingsComponent(LeverId.LIBRARIAN, band, measured = false)
    }

    /**
     * Lever 2 — IDE index tools replacing grep + reads. Standard CC would grep and then read the
     * hit files INTO THE MAIN CONTEXT: the first read at full input rate (same as the librarian
     * counterfactual), then re-ridden on later turns at cache-read rate. ClawDEA's index tool
     * returns a compact result instead, so the avoided cost is the first read plus the re-rides.
     *
     * Band: low = one hit's first read (full rate, no re-rides); expected = all hits' first read
     * plus re-rides over the remaining turns at cache-read rate; high = expected scaled by the
     * grep-scan factor (the avoided full-tree scan pass).
     *
     * NOTE: pricing the first read at cache-read rate (0.1x) previously made a typical call worth a
     * fraction of a cent, so this lever always rounded to $0.00 — the first read is real, full-rate
     * input, not a cache hit.
     */
    fun indexTools(obs: TurnObservation): SavingsComponent {
        if (obs.indexTools.isEmpty()) return SavingsComponent(LeverId.INDEX_TOOLS, SavingsBand.ZERO, measured = false)
        val perInputToken = ModelPricing.rateFor(obs.model).inputPerM / 1_000_000.0
        val cacheRead = perInputToken * ModelPricing.CACHE_READ_MULTIPLIER
        val reRides = obs.remainingTurns.coerceAtLeast(0)
        var band = SavingsBand.ZERO
        for (t in obs.indexTools) {
            val firstReadAll = t.hitFilesTokens * perInputToken
            val firstReadOne = if (t.hitCount > 0) (t.hitFilesTokens.toDouble() / t.hitCount) * perInputToken else firstReadAll
            val reRideExpected = t.hitFilesTokens * cacheRead * reRides
            val low = firstReadOne
            val expected = firstReadAll + reRideExpected
            val high = expected * GREP_SCAN_FACTOR
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
