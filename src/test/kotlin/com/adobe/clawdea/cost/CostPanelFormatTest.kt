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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CostPanelFormatTest {

    @Test
    fun `spend gauge shows used of limit currency and percent`() {
        val u = SubscriptionUsage(true, spend = SubscriptionUsage.Spend(54652.0, 60000.0, 91, "USD"))
        val h = CostPanelFormat.subscriptionHeader(u)
        assertEquals("54652.00 of 60000.00 USD · 91% used", h)
    }

    @Test
    fun `windows header lists each window pct`() {
        val u = SubscriptionUsage(
            true,
            windows = listOf(UsageWindow("5-hour", 21, 0), UsageWindow("7-day", 40, 0)),
        )
        assertEquals("5-hour 21% · 7-day 40%", CostPanelFormat.subscriptionHeader(u))
    }

    @Test
    fun `unavailable usage falls back to marker`() {
        assertEquals("usage unavailable", CostPanelFormat.subscriptionHeader(SubscriptionUsage.UNAVAILABLE))
    }

    @Test
    fun `bedrock header shows mtd all-time and since`() {
        val t = ProviderTotal("2026-05-15", 123.40, 512.88, "2026-06")
        val h = CostPanelFormat.bedrockHeader(t)
        assertTrue(h.contains("$123.40"))
        assertTrue(h.contains("$512.88"))
        assertTrue(h.contains("2026-05-15"))
    }
}
