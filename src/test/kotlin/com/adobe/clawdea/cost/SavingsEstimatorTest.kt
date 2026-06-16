package com.adobe.clawdea.cost

import org.junit.Assert.assertEquals
import org.junit.Test

class SavingsEstimatorTest {

    @Test
    fun `band plus band adds componentwise`() {
        val a = SavingsBand(1.0, 2.0, 3.0)
        val b = SavingsBand(0.5, 0.5, 0.5)
        val sum = a + b
        assertEquals(1.5, sum.low, 1e-9)
        assertEquals(2.5, sum.expected, 1e-9)
        assertEquals(3.5, sum.high, 1e-9)
    }

    @Test
    fun `band negate flips and swaps low high`() {
        val a = SavingsBand(1.0, 2.0, 5.0)
        val n = -a
        assertEquals(-5.0, n.low, 1e-9)
        assertEquals(-2.0, n.expected, 1e-9)
        assertEquals(-1.0, n.high, 1e-9)
    }

    @Test
    fun `exact builds a zero-width band`() {
        val e = SavingsBand.exact(-0.05)
        assertEquals(-0.05, e.low, 1e-9)
        assertEquals(-0.05, e.expected, 1e-9)
        assertEquals(-0.05, e.high, 1e-9)
    }

    @Test
    fun `turn observation defaults are empty and zero`() {
        val t = TurnObservation(model = "claude-opus-4-8")
        assertEquals("claude-opus-4-8", t.model)
        assertEquals(0, t.remainingTurns)
        assertEquals(0, t.primerCacheReadTokens)
        assert(t.subagents.isEmpty())
        assert(t.indexTools.isEmpty())
    }

    @Test
    fun `component carries lever id and band`() {
        val c = SavingsComponent(LeverId.LIBRARIAN, SavingsBand(-0.1, 0.2, 0.5), measured = false)
        assertEquals(LeverId.LIBRARIAN, c.leverId)
        assertEquals(0.2, c.band.expected, 1e-9)
        assertEquals(false, c.measured)
    }

    @Test
    fun `librarian on a one-shot question nets negative`() {
        val sub = SubagentObservation(
            agentType = "wiki-librarian",
            costUsd = 0.04,
            summaryTokens = 600,
            filesReadTokens = 0,
            inputTokens = 4000,
        )
        val obs = TurnObservation(model = "claude-opus-4-8", remainingTurns = 0, subagents = listOf(sub))
        val c = SavingsEstimator.librarian(obs)
        assert(c.band.expected < 0.0) { "expected net should be negative, was ${c.band.expected}" }
        assertEquals(false, c.measured)
        assertEquals(LeverId.LIBRARIAN, c.leverId)
    }

    @Test
    fun `librarian in a long exploratory session nets positive`() {
        val sub = SubagentObservation(
            agentType = "wiki-librarian",
            costUsd = 0.04,
            summaryTokens = 600,
            filesReadTokens = 30_000,
            inputTokens = 35_000,
        )
        val obs = TurnObservation(model = "claude-opus-4-8", remainingTurns = 8, subagents = listOf(sub))
        val c = SavingsEstimator.librarian(obs)
        assert(c.band.expected > 0.0) { "expected net should be positive, was ${c.band.expected}" }
        assert(c.band.low <= c.band.expected) { "${c.band.low} <= ${c.band.expected}" }
        assert(c.band.expected <= c.band.high) { "${c.band.expected} <= ${c.band.high}" }
    }

    @Test
    fun `librarian with no subagents is zero`() {
        val obs = TurnObservation(model = "claude-opus-4-8")
        assertEquals(SavingsBand.ZERO, SavingsEstimator.librarian(obs).band)
    }
}
