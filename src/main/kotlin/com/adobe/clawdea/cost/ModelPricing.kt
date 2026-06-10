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
 * Per-model token pricing. The Claude Code transcript does not persist a dollar
 * figure, and subscription/bedrock plans report total_cost_usd = 0, so cost is
 * computed from token usage with these published per-million-token rates.
 *
 * Rates current as of 2026-06. DRIFT-WATCHED: scripts/drift/watchlist.yaml entry
 * `model-pricing` flags this table when the published pricing page changes.
 * Cache-read tokens bill at 0.1x input; cache-creation (5-min) at 1.25x input.
 */
object ModelPricing {

    /** USD per 1,000,000 tokens. */
    data class Rate(val inputPerM: Double, val outputPerM: Double)

    // Longest-prefix match wins; keep more specific ids before generic ones if overlap.
    private val rates: List<Pair<String, Rate>> = listOf(
        "claude-fable-5" to Rate(10.0, 50.0),
        "claude-opus-4-8" to Rate(5.0, 25.0),
        "claude-opus-4-7" to Rate(5.0, 25.0),
        "claude-opus-4-6" to Rate(5.0, 25.0),
        "claude-opus" to Rate(5.0, 25.0),
        "claude-sonnet-4-6" to Rate(3.0, 15.0),
        "claude-sonnet" to Rate(3.0, 15.0),
        "claude-haiku-4-5" to Rate(1.0, 5.0),
        "claude-haiku" to Rate(1.0, 5.0),
    )

    /** Conservative fallback for unrecognized ids: never price to 0 (that hides cost). */
    private val fallback = Rate(5.0, 25.0)

    private const val CACHE_READ_MULTIPLIER = 0.1
    private const val CACHE_CREATION_MULTIPLIER = 1.25

    fun rateFor(model: String): Rate {
        val id = model.lowercase()
        return rates.firstOrNull { id.startsWith(it.first) }?.second ?: fallback
    }

    fun costFor(
        model: String,
        inputTokens: Long,
        outputTokens: Long,
        cacheReadTokens: Long,
        cacheCreationTokens: Long,
    ): Double {
        val r = rateFor(model)
        val perToken = r.inputPerM / 1_000_000.0
        val outPerToken = r.outputPerM / 1_000_000.0
        return inputTokens * perToken +
            outputTokens * outPerToken +
            cacheReadTokens * perToken * CACHE_READ_MULTIPLIER +
            cacheCreationTokens * perToken * CACHE_CREATION_MULTIPLIER
    }

    // Convenience overload for Int token counts (parser emits Int).
    fun costFor(model: String, inputTokens: Int, outputTokens: Int, cacheReadTokens: Int, cacheCreationTokens: Int): Double =
        costFor(model, inputTokens.toLong(), outputTokens.toLong(), cacheReadTokens.toLong(), cacheCreationTokens.toLong())
}
