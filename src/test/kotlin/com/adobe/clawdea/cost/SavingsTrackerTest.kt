package com.adobe.clawdea.cost

import org.junit.Assert.assertEquals
import org.junit.Test

class SavingsTrackerTest {

    @Test
    fun `accrue sums session band across turns`() {
        var session = SavingsBand.ZERO
        val obs1 = TurnObservation("claude-opus-4-8", primerCacheReadTokens = 4000)
        val obs2 = TurnObservation("claude-opus-4-8", primerCacheReadTokens = 4000)
        session += SavingsEstimator.aggregate(obs1)
        session += SavingsEstimator.aggregate(obs2)
        assert(session.expected < 0.0)
        assertEquals(SavingsEstimator.aggregate(obs1).expected * 2, session.expected, 1e-9)
    }

    @Test
    fun `cumulative accrues the same net as the session aggregate`() {
        val obs = TurnObservation(
            "claude-opus-4-8",
            remainingTurns = 4,
            subagents = listOf(SubagentObservation("wiki-librarian", 0.02, 600, 25_000, 27_000)),
        )
        val net = SavingsEstimator.aggregate(obs)
        val total = SavingsTotal.empty().add(net, "2026-06-16", "2026-06")
        assertEquals(net.expected, total.allTime.expected, 1e-9)
        assertEquals(net.expected, total.mtd.expected, 1e-9)
    }
}
