package com.adobe.clawdea.cost

import org.junit.Assert.*
import org.junit.Test

class CostTrackerTest {

    @Test
    fun `parseResultLine extracts cost model and tokens from stream-json stdout`() {
        val stdout = """
            {"type":"system","subtype":"init","session_id":"s"}
            {"type":"assistant","message":{"model":"claude-opus-4-8","usage":{"input_tokens":5,"output_tokens":7,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}
            {"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.0321,"session_id":"s","usage":{"input_tokens":5,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":7}}
        """.trimIndent()
        val r = CostTracker.parseResultLine(stdout)
        assertNotNull(r)
        assertEquals("claude-opus-4-8", r!!.model)
        assertEquals(0.0321, r.costUsd, 1e-9)
        assertEquals(5, r.inputTokens)
        assertEquals(7, r.outputTokens)
    }

    @Test
    fun `parseResultLine returns null when no result line present`() {
        assertNull(CostTracker.parseResultLine("{\"type\":\"system\"}\n{\"type\":\"assistant\",\"message\":{}}"))
        assertNull(CostTracker.parseResultLine(""))
    }

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
        assertEquals(0.42, CostTracker.effectiveTurnCost("claude-opus-4-8", 0.42, 1000, 1000, 0, 0, 0, "anthropic"), 1e-9)
    }

    @Test
    fun `effectiveTurnCost computes from tokens when reported is zero`() {
        // opus output $25/M: 1,000,000 output = 25.0
        assertEquals(25.0, CostTracker.effectiveTurnCost("claude-opus-4-8", 0.0, 0, 1_000_000, 0, 0, 0, "anthropic"), 1e-6)
    }


    @Test
    fun `perTurnDelta differences the CLI cumulative into a per-turn marginal cost`() {
        // First real-dollar turn of a process (no baseline yet): full reported figure.
        val t1 = CostTracker.perTurnDelta(reportedCumulative = 0.31, lastBaseline = -1.0, notionalUsd = 0.0)
        assertEquals(0.31, t1.delta, 1e-9)
        assertEquals(0.31, t1.newBaseline, 1e-9)

        // Second turn: cumulative grew to 0.35 → this turn cost the difference, baseline advances.
        val t2 = CostTracker.perTurnDelta(reportedCumulative = 0.35, lastBaseline = t1.newBaseline, notionalUsd = 0.0)
        assertEquals(0.04, t2.delta, 1e-9)
        assertEquals(0.35, t2.newBaseline, 1e-9)
    }

    @Test
    fun `perTurnDelta rebases when the cumulative goes backwards (resume or restart)`() {
        // A new CLI process resets its counter; reported (0.025) < baseline (0.35). Treat the
        // reported figure as this turn's cost and rebase — never emit a negative delta.
        val d = CostTracker.perTurnDelta(reportedCumulative = 0.025, lastBaseline = 0.35, notionalUsd = 0.0)
        assertEquals(0.025, d.delta, 1e-9)
        assertEquals(0.025, d.newBaseline, 1e-9)
    }

    @Test
    fun `perTurnDelta uses the notional token price on flat-rate turns and preserves the baseline`() {
        // Subscription/Bedrock report 0 → no cumulative to diff. Delta is the notional figure and
        // the baseline is left untouched so a later real-dollar turn re-establishes it.
        val d = CostTracker.perTurnDelta(reportedCumulative = 0.0, lastBaseline = -1.0, notionalUsd = 0.12)
        assertEquals(0.12, d.delta, 1e-9)
        assertEquals(-1.0, d.newBaseline, 1e-9)
    }

    @Test
    fun `perTurnDelta summed across a session equals the last cumulative`() {
        // The whole point of the fix: summing per-turn deltas reproduces the final cumulative,
        // instead of summing the cumulative itself (which over-counts quadratically).
        val cumulatives = listOf(0.10, 0.24, 0.24, 0.51, 0.60)
        var baseline = -1.0
        var summed = 0.0
        for (c in cumulatives) {
            val d = CostTracker.perTurnDelta(c, baseline, notionalUsd = 0.0)
            summed += d.delta
            baseline = d.newBaseline
        }
        assertEquals(cumulatives.last(), summed, 1e-9)
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
