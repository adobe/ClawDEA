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

/** Soft-warning band derived from spend vs budget (or subscription window). */
enum class CostBand { NEUTRAL, GREEN, AMBER, RED }

/** Subscription rate-limit windows from the Anthropic oauth/usage endpoint. */
data class SubscriptionWindow(
    val fiveHourPct: Int,
    val sevenDayPct: Int,
    val resetEpochMs: Long,
)

/**
 * One rate-limit window from `oauth/usage` (e.g. `five_hour`, `seven_day`, `seven_day_opus`).
 * [label] is a friendly display name; [pct] is the utilization percent (0–100, rounded from
 * the endpoint's Double); [resetEpochMs] is 0 when the endpoint reported no reset time.
 */
data class UsageWindow(val label: String, val pct: Int, val resetEpochMs: Long)

/**
 * Parsed Anthropic `oauth/usage` result. The endpoint returns a flat object of window keys
 * (each null or `{utilization, resets_at}`) plus an `extra_usage` spend object. We surface:
 *  - [spend] from `extra_usage` (used vs monthly limit, in `currency`) — the headline gauge.
 *  - [windows] the non-null, user-relevant rate-limit windows (internal codename keys are dropped).
 * [available] is false when the endpoint failed/empty and the UI should show a notional estimate.
 *
 * NOTE: schema captured from a live response (src/test/resources/oauth-usage/live-sample.json);
 * undocumented and drift-watched. `utilization` is a Double percent; `monthly_limit`/`used_credits`
 * are in `currency` units (labeled "credits" — display with the currency code, do not assume "$").
 */
data class SubscriptionUsage(
    val available: Boolean,
    val spend: Spend? = null,
    val windows: List<UsageWindow> = emptyList(),
    val lastUpdatedEpochMs: Long = 0,
) {
    /** From `extra_usage`: [used]/[limit] in [currency]; [pct] is the reported utilization (0–100). */
    data class Spend(val used: Double, val limit: Double, val pct: Int, val currency: String)
    companion object { val UNAVAILABLE = SubscriptionUsage(available = false) }
}

/** One provider's header data for the Cost Control panel. */
data class ProviderBlock(
    val providerId: String,
    val total: ProviderTotal?,
    val usage: SubscriptionUsage = SubscriptionUsage.UNAVAILABLE,
)

/** Immutable view published to the UI after every cost update. */
data class CostSnapshot(
    val providerId: String,
    val sessionUsd: Double,
    val dailyUsd: Double,
    val dailyBudgetUsd: Double,
    val band: CostBand,
    val perModelUsd: Map<String, Double>,
    val window: SubscriptionWindow? = null,
    /**
     * The model that the "Default" selection actually resolved to — set only after a
     * turn ran with no explicit model override (the CLI chose). Null until such a turn
     * is observed. Lets the selector honestly label its "Default" entry, e.g.
     * "Default (Opus 4.8)". Deliberately NOT set from explicit selections or from
     * resumed history, where the model reflects a past choice, not today's default.
     */
    val defaultResolvedModel: String? = null,
    /** Live subscription usage (enterprise spend or pro windows); UNAVAILABLE until polled. */
    val usage: SubscriptionUsage = SubscriptionUsage.UNAVAILABLE,
    /** Per-provider cumulative totals for the current provider, or null for none. */
    val providerTotal: ProviderTotal? = null,
    /** Knowledge-upkeep sub-totals for this chat, keyed by bucket. */
    val knowledgeUsd: Map<KnowledgeBucket, Double> = emptyMap(),
)
