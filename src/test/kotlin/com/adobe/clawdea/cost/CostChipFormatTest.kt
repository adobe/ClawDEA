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
    fun `subscription with window shows window percent and notional chat`() {
        val s = CostSnapshot("subscription", sessionUsd = 0.41, dailyUsd = 0.41,
            dailyBudgetUsd = 0.0, band = CostBand.AMBER, perModelUsd = emptyMap(),
            window = SubscriptionWindow(fiveHourPct = 38, sevenDayPct = 12, resetEpochMs = 0))
        assertEquals("window 38% · ≈\$0.41 chat", CostChip.formatText(s))
    }

    @Test
    fun `subscription without window falls back to notional dollars`() {
        val s = CostSnapshot("subscription", sessionUsd = 0.41, dailyUsd = 0.41,
            dailyBudgetUsd = 0.0, band = CostBand.NEUTRAL, perModelUsd = emptyMap())
        assertEquals("≈\$0.41 chat", CostChip.formatText(s))
    }
}
