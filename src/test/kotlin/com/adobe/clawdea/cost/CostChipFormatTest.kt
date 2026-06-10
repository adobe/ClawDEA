package com.adobe.clawdea.cost

import org.junit.Assert.*
import org.junit.Test

class CostChipFormatTest {

    @Test
    fun `dollar provider shows today and chat`() {
        val s = CostSnapshot("anthropic", sessionUsd = 0.41, dailyUsd = 1.24,
            dailyBudgetUsd = 5.0, band = CostBand.GREEN, perModelUsd = emptyMap())
        assertEquals("$1.24 today · $0.41 chat", CostChip.formatText(s))
    }

    @Test
    fun `subscription with spend gauge shows usage percent and notional chat`() {
        val s = CostSnapshot("subscription", sessionUsd = 0.41, dailyUsd = 0.41,
            dailyBudgetUsd = 0.0, band = CostBand.RED, perModelUsd = emptyMap(),
            usage = SubscriptionUsage(true, spend = SubscriptionUsage.Spend(54652.0, 60000.0, 91, "USD")))
        assertEquals("usage 91% · ≈\$0.41 chat", CostChip.formatText(s))
    }

    @Test
    fun `subscription with windows shows worst window percent`() {
        val s = CostSnapshot("subscription", sessionUsd = 0.41, dailyUsd = 0.41,
            dailyBudgetUsd = 0.0, band = CostBand.AMBER, perModelUsd = emptyMap(),
            usage = SubscriptionUsage(true, windows = listOf(
                UsageWindow("5-hour", 38, 0), UsageWindow("7-day", 12, 0))))
        assertEquals("usage 38% · ≈\$0.41 chat", CostChip.formatText(s))
    }

    @Test
    fun `subscription without usage falls back to notional dollars`() {
        val s = CostSnapshot("subscription", sessionUsd = 0.41, dailyUsd = 0.41,
            dailyBudgetUsd = 0.0, band = CostBand.NEUTRAL, perModelUsd = emptyMap())
        assertEquals("≈\$0.41 chat", CostChip.formatText(s))
    }
}
