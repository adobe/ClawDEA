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

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.time.Instant

object OAuthUsageClient {
    private val log = logger<OAuthUsageClient>()
    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"

    /**
     * Rate-limit window keys we surface, in display order, with friendly labels. The endpoint
     * also returns internal codename keys (tangelo, iguana_necktie, cinder_cove, *_omelette, …);
     * those are deliberately NOT listed here so the UI never shows them.
     */
    private val WINDOW_LABELS: List<Pair<String, String>> = listOf(
        "five_hour" to "5-hour",
        "seven_day" to "7-day",
        "seven_day_opus" to "7-day Opus",
        "seven_day_sonnet" to "7-day Sonnet",
    )

    /**
     * Fetch + parse live usage. Returns UNAVAILABLE on no-token / non-200 / IO error.
     * Must be called off-EDT. Read-only; reuses SubscriptionModelProbe's token source.
     * The token is used only as a Bearer header — never logged or persisted.
     */
    fun fetch(
        tokenSource: () -> String? = {
            com.adobe.clawdea.gateway.SubscriptionModelProbe.defaultTokenSource(
                com.adobe.clawdea.gateway.SubscriptionModelProbe.defaultCredentialsFile(),
            )
        },
        timeoutMs: Int = 5000,
    ): SubscriptionUsage {
        val token = tokenSource()
        if (token == null) {
            // No subscription token present (e.g. user is API-key / bedrock only). Expected, not
            // an error — debug level so it never clutters the log but is there when needed.
            log.debug("oauth/usage: no subscription token; skipping fetch")
            return SubscriptionUsage.UNAVAILABLE
        }
        return try {
            val conn = java.net.URI(USAGE_URL).toURL().openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("anthropic-beta", "oauth-2025-04-20")
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code != 200) {
                    log.info("oauth/usage: HTTP $code; reporting unavailable")
                    return SubscriptionUsage.UNAVAILABLE
                }
                parse(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            // Token never logged; only the failure class/message (e.g. SocketTimeoutException).
            log.info("oauth/usage: ${t.javaClass.simpleName}: ${t.message}")
            SubscriptionUsage.UNAVAILABLE
        }
    }

    /**
     * Parse an oauth/usage JSON body into [SubscriptionUsage]. Returns UNAVAILABLE on malformed
     * input or when neither a spend gauge nor any known non-null window is present. Schema is the
     * live shape: flat window keys (`{utilization, resets_at}` | null) plus an `extra_usage` object.
     */
    fun parse(json: String): SubscriptionUsage {
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return SubscriptionUsage.UNAVAILABLE
            val o = root.asJsonObject

            // Spend gauge from extra_usage (the headline number). used_credits/monthly_limit are
            // in CENTS of `currency` (e.g. 54652 = $546.52, 60000 = $600.00) — divide by 100 so
            // Spend.used/limit are canonical dollar amounts everywhere downstream.
            val spend = o.get("extra_usage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { eu ->
                val limitCents = eu.get("monthly_limit")?.takeIf { it.isJsonPrimitive }?.asDouble
                val usedCents = eu.get("used_credits")?.takeIf { it.isJsonPrimitive }?.asDouble
                if (limitCents == null || usedCents == null) return@let null
                val pct = eu.get("utilization")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
                val currency = eu.get("currency")?.takeIf { it.isJsonPrimitive }?.asString ?: "USD"
                SubscriptionUsage.Spend(usedCents / 100.0, limitCents / 100.0, Math.round(pct).toInt(), currency)
            }

            // Known rate-limit windows that are present (non-null). Codename keys are ignored.
            val windows = WINDOW_LABELS.mapNotNull { (key, label) ->
                val w = o.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val pct = w.get("utilization")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return@mapNotNull null
                UsageWindow(label, Math.round(pct).toInt(), epochMs(w.get("resets_at")?.takeIf { it.isJsonPrimitive }?.asString))
            }

            if (spend == null && windows.isEmpty()) SubscriptionUsage.UNAVAILABLE
            else SubscriptionUsage(available = true, spend = spend, windows = windows)
        } catch (_: Exception) {
            SubscriptionUsage.UNAVAILABLE
        }
    }

    private fun epochMs(iso: String?): Long =
        try { if (iso.isNullOrBlank()) 0 else Instant.parse(iso).toEpochMilli() } catch (_: Exception) { 0 }
}
