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
 * A signed savings estimate with a confidence range. Positive = saving, negative = cost.
 * [low] <= [expected] <= [high] always holds for a well-formed band.
 */
data class SavingsBand(val low: Double, val expected: Double, val high: Double) {
    operator fun plus(o: SavingsBand) = SavingsBand(low + o.low, expected + o.expected, high + o.high)

    /** Negation swaps edges so the invariant low <= expected <= high is preserved. */
    operator fun unaryMinus() = SavingsBand(-high, -expected, -low)

    companion object {
        val ZERO = SavingsBand(0.0, 0.0, 0.0)

        /** A measured (zero-variance) value. */
        fun exact(v: Double) = SavingsBand(v, v, v)
    }
}

/** The four modeled levers. Order is the display order in the panel. */
enum class LeverId { LIBRARIAN, INDEX_TOOLS, KNOWLEDGE_UPKEEP, PRIMER_OVERHEAD }

/**
 * One subagent dispatch observed in a turn. [costUsd] is the subagent's own real turn cost
 * (effectiveTurnCost over its result event). [summaryTokens] is the size of the text it
 * returned to the main loop. [filesReadTokens] is the token size of the files the subagent
 * itself read (proxy for what inline exploration would have pulled); 0 when it read none.
 * [inputTokens] is the subagent's own input volume — the fallback proxy when filesReadTokens is 0.
 */
data class SubagentObservation(
    val agentType: String,
    val costUsd: Double,
    val summaryTokens: Int,
    val filesReadTokens: Int,
    val inputTokens: Int,
)

/** One IDE index tool call. [hitCount] result rows; [hitFilesTokens] estimated token size of those files. */
data class IndexToolObservation(val toolName: String, val hitCount: Int, val hitFilesTokens: Int)

/** Everything the estimator needs about a single turn. Built by TurnObservationBuilder. */
data class TurnObservation(
    val model: String,
    /** Turns remaining in the session AFTER this one — drives the re-ride multiplier. */
    val remainingTurns: Int = 0,
    val subagents: List<SubagentObservation> = emptyList(),
    val indexTools: List<IndexToolObservation> = emptyList(),
    /** Primer (wiki TOC + REPO_STATE) tokens read from cache this turn (billed at 0.1x). */
    val primerCacheReadTokens: Int = 0,
    /** Primer tokens written to cache this turn (billed at 1.25x) — first turn / cache miss. */
    val primerCacheCreationTokens: Int = 0,
    /** Knowledge-upkeep dollars this turn (read from KnowledgeBucket accounting; already measured). */
    val knowledgeUpkeepUsd: Double = 0.0,
)

/** A per-lever estimate. [measured] = true → zero-variance (band low==expected==high). */
data class SavingsComponent(val leverId: LeverId, val band: SavingsBand, val measured: Boolean)
