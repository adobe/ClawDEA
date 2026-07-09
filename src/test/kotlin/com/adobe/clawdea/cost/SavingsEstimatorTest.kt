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

    @Test
    fun `index tools save the avoided file reads`() {
        val obs = TurnObservation(
            model = "claude-opus-4-8",
            indexTools = listOf(IndexToolObservation("find_usages", hitCount = 5, hitFilesTokens = 12_000)),
        )
        val c = SavingsEstimator.indexTools(obs)
        assert(c.band.expected > 0.0)
        assert(c.band.low <= c.band.expected && c.band.expected <= c.band.high)
        assertEquals(false, c.measured)
    }

    @Test
    fun `index tools price the first read at full input rate not cache rate`() {
        // Regression: this lever was priced entirely at cache-read rate (0.1x),
        // so a typical call was a fraction of a cent and always rounded to $0.00.
        // The first read into the main context is real full-rate input.
        val tokens = 12_000
        val obs = TurnObservation(
            model = "claude-opus-4-8", // Opus input $5/MTok → $5e-6 / token
            remainingTurns = 0,        // no re-rides: expected is exactly the first read
            indexTools = listOf(IndexToolObservation("find_usages", hitCount = 5, hitFilesTokens = tokens)),
        )
        val perInputToken = 5.0 / 1_000_000.0
        val fullRateFirstRead = tokens * perInputToken // $0.06
        val c = SavingsEstimator.indexTools(obs)
        assertEquals(fullRateFirstRead, c.band.expected, 1e-9)
        // A meaningful, non-rounding-to-zero figure (10x the old cache-rate value).
        assert(c.band.expected >= 0.01) { "index-tools saving should be visible, was ${c.band.expected}" }
    }

    @Test
    fun `index tools add cache-rate re-rides for the remaining turns`() {
        val tokens = 10_000
        val base = TurnObservation(
            model = "claude-opus-4-8",
            remainingTurns = 0,
            indexTools = listOf(IndexToolObservation("find_symbol", hitCount = 2, hitFilesTokens = tokens)),
        )
        val withReRides = base.copy(remainingTurns = 3)
        val perInputToken = 5.0 / 1_000_000.0
        val cacheRead = perInputToken * ModelPricing.CACHE_READ_MULTIPLIER
        val delta = SavingsEstimator.indexTools(withReRides).band.expected -
            SavingsEstimator.indexTools(base).band.expected
        assertEquals(tokens * cacheRead * 3, delta, 1e-9)
    }

    @Test
    fun `primer overhead is a measured cost`() {
        val obs = TurnObservation(
            model = "claude-opus-4-8",
            primerCacheReadTokens = 4000,
            primerCacheCreationTokens = 500,
        )
        val c = SavingsEstimator.primerOverhead(obs)
        assert(c.band.expected < 0.0)
        assertEquals(c.band.low, c.band.high, 1e-12)
        assertEquals(true, c.measured)
    }

    @Test
    fun `knowledge upkeep is a measured cost equal to the dollars spent`() {
        val obs = TurnObservation(model = "claude-opus-4-8", knowledgeUpkeepUsd = 0.05)
        val c = SavingsEstimator.knowledgeUpkeep(obs)
        assertEquals(-0.05, c.band.expected, 1e-9)
        assertEquals(true, c.measured)
    }

    @Test
    fun `aggregate sums all levers and only modeled levers widen the band`() {
        val obs = TurnObservation(
            model = "claude-opus-4-8",
            remainingTurns = 3,
            subagents = listOf(SubagentObservation("wiki-librarian", 0.02, 600, 20_000, 22_000)),
            indexTools = listOf(IndexToolObservation("find_symbol", 3, 9000)),
            primerCacheReadTokens = 4000,
            knowledgeUpkeepUsd = 0.0,
        )
        val agg = SavingsEstimator.aggregate(obs)
        val lib = SavingsEstimator.librarian(obs).band
        val idx = SavingsEstimator.indexTools(obs).band
        val expectedWidth = (lib.high - lib.low) + (idx.high - idx.low)
        assertEquals(expectedWidth, agg.high - agg.low, 1e-9)
    }

    @Test
    fun `confidence is high when band is narrow relative to magnitude`() {
        assertEquals(Confidence.ESTIMATE, SavingsEstimator.confidence(SavingsBand(0.98, 1.00, 1.02)))
    }

    @Test
    fun `confidence is rough when band is wide relative to magnitude`() {
        assertEquals(Confidence.ROUGH, SavingsEstimator.confidence(SavingsBand(-0.25, 0.5, 1.25)))
    }

    @Test
    fun `confidence near zero magnitude is rough`() {
        assertEquals(Confidence.ROUGH, SavingsEstimator.confidence(SavingsBand(-0.01, 0.0, 0.01)))
    }

    @Test
    fun `confidence at exact boundary relWidth one half is estimate`() {
        // width 0.5 on expected 1.0 → relWidth exactly 0.5 → ESTIMATE (boundary is inclusive).
        assertEquals(Confidence.ESTIMATE, SavingsEstimator.confidence(SavingsBand(0.75, 1.0, 1.25)))
    }

    @Test
    fun `librarian sums across multiple subagents`() {
        val s = SubagentObservation("wiki-librarian", 0.01, 500, 15_000, 16_000)
        val one = TurnObservation(model = "claude-opus-4-8", remainingTurns = 4, subagents = listOf(s))
        val two = TurnObservation(model = "claude-opus-4-8", remainingTurns = 4, subagents = listOf(s, s))
        val bandOne = SavingsEstimator.librarian(one).band
        val bandTwo = SavingsEstimator.librarian(two).band
        assertEquals(bandOne.expected * 2, bandTwo.expected, 1e-9)
        assertEquals(bandOne.low * 2, bandTwo.low, 1e-9)
        assertEquals(bandOne.high * 2, bandTwo.high, 1e-9)
    }

    @Test
    fun `index tools sum across multiple tools`() {
        val t = IndexToolObservation("find_symbol", 3, 9000)
        val one = TurnObservation(model = "claude-opus-4-8", indexTools = listOf(t))
        val two = TurnObservation(model = "claude-opus-4-8", indexTools = listOf(t, t))
        val a = SavingsEstimator.indexTools(one).band
        val b = SavingsEstimator.indexTools(two).band
        assertEquals(a.expected * 2, b.expected, 1e-9)
    }

    @Test
    fun `librarian prefers filesReadTokens over inputTokens when present`() {
        // filesReadTokens dominates; inputTokens must be ignored when files were read.
        val withFiles = SubagentObservation("wiki-librarian", 0.0, 0, 10_000, 99_999)
        val obs = TurnObservation(model = "claude-opus-4-8", remainingTurns = 0, subagents = listOf(withFiles))
        val band = SavingsEstimator.librarian(obs).band
        // Compare against an explicit observation that reads the SAME 10_000 tokens but tiny input.
        val control = SubagentObservation("wiki-librarian", 0.0, 0, 10_000, 1)
        val controlBand = SavingsEstimator.librarian(
            TurnObservation(model = "claude-opus-4-8", remainingTurns = 0, subagents = listOf(control)),
        ).band
        assertEquals(controlBand.expected, band.expected, 1e-9)
    }

    @Test
    fun `index tools zero hit count falls back to full file tokens`() {
        val obs = TurnObservation(
            model = "claude-opus-4-8",
            indexTools = listOf(IndexToolObservation("get_diagnostics", hitCount = 0, hitFilesTokens = 8000)),
        )
        val band = SavingsEstimator.indexTools(obs).band
        // With hitCount 0, perHit == hitFilesTokens, so low == expected.
        assertEquals(band.low, band.expected, 1e-9)
    }

    @Test
    fun `components returns the four levers in display order`() {
        val obs = TurnObservation(model = "claude-opus-4-8")
        val ids = SavingsEstimator.components(obs).map { it.leverId }
        assertEquals(
            listOf(LeverId.LIBRARIAN, LeverId.INDEX_TOOLS, LeverId.KNOWLEDGE_UPKEEP, LeverId.PRIMER_OVERHEAD),
            ids,
        )
    }

    @Test
    fun `aggregate expected equals sum of component expecteds`() {
        val obs = TurnObservation(
            model = "claude-opus-4-8",
            remainingTurns = 2,
            subagents = listOf(SubagentObservation("wiki-librarian", 0.02, 600, 20_000, 22_000)),
            indexTools = listOf(IndexToolObservation("find_symbol", 3, 9000)),
            primerCacheReadTokens = 4000,
            knowledgeUpkeepUsd = 0.03,
        )
        val sum = SavingsEstimator.components(obs).sumOf { it.band.expected }
        assertEquals(sum, SavingsEstimator.aggregate(obs).expected, 1e-9)
    }

    @Test
    fun `snapshot exposes session band, cumulative, and components`() {
        val snap = SavingsSnapshot(
            sessionBand = SavingsBand(0.1, 0.2, 0.3),
            cumulative = SavingsTotal.empty(),
            leverBands = mapOf(LeverId.INDEX_TOOLS to SavingsBand(0.05, 0.1, 0.15)),
            components = listOf(SavingsComponent(LeverId.PRIMER_OVERHEAD, SavingsBand.exact(-0.02), true)),
            turnCount = 5,
        )
        assertEquals(0.2, snap.sessionBand.expected, 1e-9)
        assertEquals(1, snap.components.size)
        assertEquals(5, snap.turnCount)
        assertEquals(true, snap.isNetSaving)
    }
}
