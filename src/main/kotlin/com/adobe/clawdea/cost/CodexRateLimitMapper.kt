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

import com.google.gson.JsonObject

/**
 * Maps the `codex app-server` `account/rateLimits/updated` (and `account/rateLimits/read`)
 * `RateLimitSnapshot` payload onto ClawDEA's [SubscriptionUsage] model, so the OpenAI ChatGPT
 * subscription gets the same live Cost Control gauge Claude has — this is the "real credits"
 * (issue 4) the `exec` backend could never provide.
 *
 * Field shapes are pinned by the generated v2 schema (`AccountRateLimitsUpdatedNotification.json`):
 *  - `individualLimit` (`SpendControlLimitSnapshot`): `used` / `limit` (strings), `remainingPercent`,
 *    `resetsAt` — the "X of Y credits" spend gauge.
 *  - `primary` / `secondary` (`RateLimitWindow`): `usedPercent`, `windowDurationMins`, `resetsAt` —
 *    the 5-hour / weekly rate-limit windows.
 *  - `credits` (`CreditsSnapshot`): `balance` / `hasCredits` / `unlimited` — used as a spend fallback
 *    when no `individualLimit` is present.
 */
object CodexRateLimitMapper {

    /** Maps a `rateLimits` object; returns null when it carries nothing displayable. */
    fun map(rateLimits: JsonObject?, nowEpochMs: Long = System.currentTimeMillis()): SubscriptionUsage? {
        rateLimits ?: return null

        val spend = mapSpend(rateLimits.obj("individualLimit"))
            ?: mapCreditsFallback(rateLimits.obj("credits"))
        val windows = listOfNotNull(
            mapWindow(rateLimits.obj("primary"), nowEpochMs),
            mapWindow(rateLimits.obj("secondary"), nowEpochMs),
        )
        if (spend == null && windows.isEmpty()) return null
        return SubscriptionUsage(
            available = true,
            spend = spend,
            windows = windows,
            lastUpdatedEpochMs = nowEpochMs,
        )
    }

    /** `individualLimit` → a credits spend gauge. `used`/`limit` are string-encoded whole numbers. */
    private fun mapSpend(limit: JsonObject?): SubscriptionUsage.Spend? {
        limit ?: return null
        val used = limit.str("used")?.toDoubleOrNull() ?: return null
        val total = limit.str("limit")?.toDoubleOrNull() ?: return null
        if (total <= 0) return null
        val remainingPct = limit.intOrNull("remainingPercent")
        val pct = remainingPct?.let { (100 - it).coerceIn(0, 100) }
            ?: ((used / total) * 100).toInt().coerceIn(0, 100)
        return SubscriptionUsage.Spend(used = used, limit = total, pct = pct, currency = "credits", isCredits = true)
    }

    /**
     * `credits.balance` fallback when there is no `individualLimit`. We only know the remaining
     * balance (not a limit), so surface it as a 0%-utilization gauge labeled with the balance —
     * enough to show "N credits remaining" without inventing a denominator.
     */
    private fun mapCreditsFallback(credits: JsonObject?): SubscriptionUsage.Spend? {
        credits ?: return null
        if (credits.boolOrFalse("unlimited")) return null
        val balance = credits.str("balance")?.toDoubleOrNull() ?: return null
        return SubscriptionUsage.Spend(used = 0.0, limit = balance, pct = 0, currency = "credits", isCredits = true)
    }

    private fun mapWindow(window: JsonObject?, nowEpochMs: Long): UsageWindow? {
        window ?: return null
        val pct = window.intOrNull("usedPercent") ?: return null
        val mins = window.intOrNull("windowDurationMins")
        val resetsAt = window.longOrNull("resetsAt") ?: 0L
        return UsageWindow(label = windowLabel(mins), pct = pct.coerceIn(0, 100), resetEpochMs = resetsAt)
    }

    /** "5h" / "7d" style label from the window duration in minutes; falls back to "rate limit". */
    internal fun windowLabel(minutes: Int?): String {
        minutes ?: return "rate limit"
        return when {
            minutes % (60 * 24) == 0 -> "${minutes / (60 * 24)}d"
            minutes % 60 == 0 -> "${minutes / 60}h"
            else -> "${minutes}m"
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.longOrNull(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.boolOrFalse(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
}
