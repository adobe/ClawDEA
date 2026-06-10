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
    fun `subscription band uses worst utilization across spend and windows`() {
        // Spend gauge dominates when it's the worst.
        val spend = SubscriptionUsage(true, spend = SubscriptionUsage.Spend(0.0, 0.0, 80, "USD"))
        assertEquals(CostBand.AMBER, CostTracker.bandForUsage(spend))
        // A window can push it to RED.
        val mixed = SubscriptionUsage(
            true,
            spend = SubscriptionUsage.Spend(0.0, 0.0, 40, "USD"),
            windows = listOf(UsageWindow("5-hour", 95, 0)),
        )
        assertEquals(CostBand.RED, CostTracker.bandForUsage(mixed))
        // All low → GREEN.
        val low = SubscriptionUsage(true, windows = listOf(UsageWindow("7-day", 10, 0)))
        assertEquals(CostBand.GREEN, CostTracker.bandForUsage(low))
        // No percentages → NEUTRAL.
        assertEquals(CostBand.NEUTRAL, CostTracker.bandForUsage(SubscriptionUsage.UNAVAILABLE))
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

    @Test fun `knowledge buckets accrue per bucket`() {
        val m = mutableMapOf<KnowledgeBucket, Double>()
        CostTracker.addKnowledge(m, KnowledgeBucket.WIKI_UPDATE, 1.50)
        CostTracker.addKnowledge(m, KnowledgeBucket.WIKI_UPDATE, 0.24)
        CostTracker.addKnowledge(m, KnowledgeBucket.WIKI_CREATE, 3.10)
        assertEquals(1.74, m[KnowledgeBucket.WIKI_UPDATE]!!, 1e-9)
        assertEquals(3.10, m[KnowledgeBucket.WIKI_CREATE]!!, 1e-9)
    }

    @Test fun `usedProviders unions stored keys with the active provider`() {
        assertEquals(
            listOf("anthropic", "bedrock"),
            CostTracker.usedProviders(stored = setOf("bedrock"), active = "anthropic").sorted(),
        )
        assertEquals(
            listOf("bedrock"),
            CostTracker.usedProviders(stored = setOf("bedrock"), active = "bedrock"),
        )
        assertEquals(
            emptyList<String>(),
            CostTracker.usedProviders(stored = emptySet(), active = ""),
        )
    }
}
