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

import java.util.Locale

/**
 * Pure formatters for the Cost Control panel headers (one shape per provider). Plain
 * strings, Locale.US throughout so output is deterministic regardless of JVM locale.
 */
object CostPanelFormat {
    private fun money(v: Double) = String.format(Locale.US, "%.2f", v)

    /**
     * Subscription header from live `oauth/usage`. Prefers the `extra_usage` spend gauge
     * ("<used> of <limit> <currency> · N% used"); otherwise lists the rate-limit windows
     * ("5-hour 21% · 7-day 40%"); falls back to a notional marker when usage is unavailable.
     * Values are credits in the reported currency — labeled with the currency code, not a "$".
     */
    fun subscriptionHeader(u: SubscriptionUsage): String {
        if (!u.available) return "usage unavailable"
        u.spend?.let { s ->
            return "${money(s.used)} of ${money(s.limit)} ${s.currency} · ${s.pct}% used"
        }
        if (u.windows.isNotEmpty()) {
            return u.windows.joinToString(" · ") { "${it.label} ${it.pct}%" }
        }
        return "usage unavailable"
    }

    /** Bedrock/API-key header from our own token-priced totals: MTD + all-time + since date. */
    fun bedrockHeader(t: ProviderTotal): String =
        "$${money(t.monthToDate)} this month · all-time $${money(t.allTime)} · since ${t.sinceDate}"
}
