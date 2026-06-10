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
import java.time.Instant

object OAuthUsageClient {
    /** Parse an oauth/usage JSON body. Returns UNAVAILABLE on any malformed/empty input. */
    fun parse(json: String): SubscriptionUsage {
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return SubscriptionUsage.UNAVAILABLE
            val o = root.asJsonObject
            val spendObj = o.getAsJsonObject("spend")
            if (spendObj != null) {
                val used = spendObj.get("used_usd")?.asDouble ?: return SubscriptionUsage.UNAVAILABLE
                val limit = spendObj.get("limit_usd")?.asDouble ?: return SubscriptionUsage.UNAVAILABLE
                val reset = epochMs(spendObj.get("resets_at")?.asString)
                return SubscriptionUsage(true, spend = SubscriptionUsage.Spend(used, limit, reset))
            }
            val winArr = o.getAsJsonArray("windows")
            if (winArr != null && winArr.size() > 0) {
                val windows = winArr.mapNotNull { el ->
                    val w = el.asJsonObject
                    val label = w.get("label")?.asString ?: return@mapNotNull null
                    val pct = w.get("utilization")?.asInt ?: return@mapNotNull null
                    UsageWindow(label, pct, epochMs(w.get("resets_at")?.asString))
                }
                if (windows.isNotEmpty()) return SubscriptionUsage(true, windows = windows)
            }
            SubscriptionUsage.UNAVAILABLE
        } catch (_: Exception) {
            SubscriptionUsage.UNAVAILABLE
        }
    }

    private fun epochMs(iso: String?): Long =
        try { if (iso.isNullOrBlank()) 0 else Instant.parse(iso).toEpochMilli() } catch (_: Exception) { 0 }
}
