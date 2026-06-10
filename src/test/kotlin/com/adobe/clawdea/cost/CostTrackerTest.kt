package com.adobe.clawdea.cost

import org.junit.Assert.*
import org.junit.Test

class CostTrackerTest {

    @Test
    fun `budget unset is neutral`() {
        assertEquals(CostBand.NEUTRAL, CostTracker.bandForDollars(daily = 10.0, budget = 0.0))
    }

    @Test
    fun `band thresholds at 75 and 90 percent`() {
        assertEquals(CostBand.GREEN, CostTracker.bandForDollars(daily = 7.4, budget = 10.0))
        assertEquals(CostBand.AMBER, CostTracker.bandForDollars(daily = 7.5, budget = 10.0))
        assertEquals(CostBand.AMBER, CostTracker.bandForDollars(daily = 8.9, budget = 10.0))
        assertEquals(CostBand.RED, CostTracker.bandForDollars(daily = 9.0, budget = 10.0))
        assertEquals(CostBand.RED, CostTracker.bandForDollars(daily = 9.1, budget = 10.0))
    }

    @Test
    fun `subscription band uses worst window pct`() {
        val w = SubscriptionWindow(fiveHourPct = 80, sevenDayPct = 40, resetEpochMs = 0)
        assertEquals(CostBand.AMBER, CostTracker.bandForWindow(w))
        assertEquals(CostBand.RED, CostTracker.bandForWindow(w.copy(fiveHourPct = 95)))
        assertEquals(CostBand.GREEN, CostTracker.bandForWindow(w.copy(fiveHourPct = 10, sevenDayPct = 10)))
    }

    @Test
    fun `daily resets when date changes`() {
        assertEquals(3.0, CostTracker.rolledDaily(storedDate = "2026-06-10", storedUsd = 2.0, today = "2026-06-10", add = 1.0), 0.0)
        assertEquals(1.0, CostTracker.rolledDaily(storedDate = "2026-06-09", storedUsd = 2.0, today = "2026-06-10", add = 1.0), 0.0)
    }

    @Test
    fun `effectiveTurnCost prefers reported cost when present`() {
        assertEquals(0.42, CostTracker.effectiveTurnCost("claude-opus-4-8", 0.42, 1000, 1000, 0, 0), 1e-9)
    }

    @Test
    fun `effectiveTurnCost computes from tokens when reported is zero`() {
        // opus output $25/M: 1,000,000 output = 25.0
        assertEquals(25.0, CostTracker.effectiveTurnCost("claude-opus-4-8", 0.0, 0, 1_000_000, 0, 0), 1e-6)
    }
}
