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
            val inlineTokens = if (s.filesReadTokens > 0) s.filesReadTokens else s.inputTokens
            val cacheRead = perInputToken * ModelPricing.CACHE_READ_MULTIPLIER
            val cacheCreate = perInputToken * ModelPricing.CACHE_CREATION_MULTIPLIER
            val avoidedLow = inlineTokens * cacheRead * 1
            val avoidedExpected = inlineTokens * cacheRead * (1 + obs.remainingTurns)
            val avoidedHigh = inlineTokens * cacheCreate * (1 + obs.remainingTurns)
            val net = SavingsBand(
                low = avoidedLow - s.costUsd,
                expected = avoidedExpected - s.costUsd,
                high = avoidedHigh - s.costUsd,
            )
            band += net
        }
        return SavingsComponent(LeverId.LIBRARIAN, band, measured = false)
    }
}
