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
 * Rates verified against the Anthropic pricing page (Claude Code v2.1.201,
 * 2026-07, issue #139): Fable 5 & Mythos 5 (10/50), Opus 4.8/4.7/4.6/4.5 (5/25),
 * Sonnet 4.6/4.5 (3/15), Haiku 4.5 (1/5). Claude Sonnet 5 carries introductory
 * pricing (2/10) through 2026-08-31 and standard pricing (3/15) from 2026-09-01,
 * modeled by the date-aware branch in [rateFor]. Mythos Preview shares the Mythos
 * tier. DRIFT-WATCHED: scripts/drift/watchlist.yaml entry `model-pricing` flags
 * this table when the published pricing page changes.
 * Cache-read tokens bill at 0.1x input; cache-creation (5-min) at 1.25x input.
 *
 * OpenAI GPT models (codex backend) are priced by family in [rateFor]: the GPT-5
 * tier (1.25/10), with cheaper -mini (0.25/2) and -nano (0.05/0.40) variants.
 */
object ModelPricing {

    /** USD per 1,000,000 tokens. */
    data class Rate(val inputPerM: Double, val outputPerM: Double)

    // Longest-prefix match wins; keep more specific ids before generic ones if overlap.
    // Sonnet 5 is matched ahead of this list in [rateFor] because its introductory
    // pricing is date-dependent.
    private val rates: List<Pair<String, Rate>> = listOf(
        "claude-fable-5" to Rate(10.0, 50.0),
        "claude-mythos-5" to Rate(10.0, 50.0),
        "claude-mythos" to Rate(10.0, 50.0), // Mythos Preview shares the Mythos tier
        "claude-opus-4-8" to Rate(5.0, 25.0),
        "claude-opus-4-7" to Rate(5.0, 25.0),
        "claude-opus-4-6" to Rate(5.0, 25.0),
        "claude-opus-4-5" to Rate(5.0, 25.0),
        "claude-opus" to Rate(5.0, 25.0),
        "claude-sonnet-4-6" to Rate(3.0, 15.0),
        "claude-sonnet-4-5" to Rate(3.0, 15.0),
        "claude-sonnet" to Rate(3.0, 15.0),
        "claude-haiku-4-5" to Rate(1.0, 5.0),
        "claude-haiku" to Rate(1.0, 5.0),
    )

    /** Conservative fallback for unrecognized ids: never price to 0 (that hides cost). */
    private val fallback = Rate(5.0, 25.0)

    // OpenAI GPT (codex backend) per-million-token rates. The account-eligible slugs
    // (gpt-5.4/5.5/5.6-{sol,terra,luna}, gpt-5, gpt-5-codex) share the GPT-5 tier; the
    // -mini / -nano variants are cheaper. Matched by family in [rateFor] (a `contains`
    // check, since the modifier can sit anywhere in the slug, e.g. "gpt-5.4-mini").
    // ChatGPT-subscription turns are flat-rate (no per-token charge), so these drive
    // only the notional cost estimate — same treatment as Claude subscription/bedrock.
    private val gpt5 = Rate(1.25, 10.0)
    private val gptMini = Rate(0.25, 2.0)
    private val gptNano = Rate(0.05, 0.40)

    // Sonnet 5 launched with introductory pricing (2/10) that reverts to the
    // standard Sonnet tier (3/15) on 2026-09-01. Both windows are modeled so cost
    // estimates stay accurate as the switchover date passes.
    private val sonnet5Intro = Rate(2.0, 10.0)
    private val sonnet5Standard = Rate(3.0, 15.0)
    private val sonnet5StandardStart: java.time.LocalDate = java.time.LocalDate.of(2026, 9, 1)

    const val CACHE_READ_MULTIPLIER = 0.1
    const val CACHE_CREATION_MULTIPLIER = 1.25

    fun rateFor(model: String): Rate = rateFor(model, java.time.LocalDate.now())

    internal fun rateFor(model: String, today: java.time.LocalDate): Rate {
        val id = model.lowercase()
        if (id.startsWith("claude-sonnet-5")) {
            return if (today.isBefore(sonnet5StandardStart)) sonnet5Intro else sonnet5Standard
        }
        if (id.startsWith("gpt")) {
            return when {
                id.contains("nano") -> gptNano
                id.contains("mini") -> gptMini
                else -> gpt5
            }
        }
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

    /**
     * Cost estimate from configured per-million-token rates. Used for OpenAI-compatible
     * providers where pricing is user-configured, not model-id-based.
     */
    fun costFor(
        rates: com.adobe.clawdea.provider.openai.profile.TokenRates,
        input: Int,
        output: Int,
        cachedInput: Int,
        reasoning: Int,
    ): Double =
        input * rates.inputPerM / 1_000_000.0 +
            output * rates.outputPerM / 1_000_000.0 +
            cachedInput * rates.cachedInputPerM / 1_000_000.0 +
            reasoning * rates.reasoningPerM / 1_000_000.0
}
