package com.adobe.clawdea.cost

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the pure accrual contract SavingsTracker depends on: a turn's net is the fold of
 * SavingsEstimator.components, session band sums across turns, and SavingsTotal.add carries the
 * same net into MTD + all-time. The @Service glue (per-chat state, persistence, publish) is a
 * thin delegation over these pure pieces; it is project-scoped and its fixture tests hang
 * headlessly (see CLAUDE.md), so it is verified at runtime, not here.
 */
class SavingsTrackerTest {

    @Test
    fun `net of a turn is the fold of component bands across turns`() {
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
