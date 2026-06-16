package com.adobe.clawdea.cost

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnObservationBuilderTest {

    @Test
    fun `estimateTokens uses chars over four`() {
        assertEquals(0, TurnObservationBuilder.estimateTokens(""))
        assertEquals(1, TurnObservationBuilder.estimateTokens("abcd"))
        assertEquals(25, TurnObservationBuilder.estimateTokens("x".repeat(100)))
    }

    @Test
    fun `index tool names are recognized`() {
        assertEquals(true, TurnObservationBuilder.isIndexTool("mcp__clawdea-intellij__find_usages"))
        assertEquals(true, TurnObservationBuilder.isIndexTool("find_symbol"))
        assertEquals(false, TurnObservationBuilder.isIndexTool("Bash"))
        assertEquals(false, TurnObservationBuilder.isIndexTool("Edit"))
    }

    @Test
    fun `build assembles a turn observation from raw signals`() {
        val obs = TurnObservationBuilder.build(
            model = "claude-opus-4-8",
            remainingTurns = 3,
            subagents = listOf(SubagentObservation("wiki-librarian", 0.02, 600, 10_000, 12_000)),
            indexTools = listOf(IndexToolObservation("find_symbol", 2, 6000)),
            primerCacheReadTokens = 4000,
            primerCacheCreationTokens = 0,
            knowledgeUpkeepUsd = 0.0,
        )
        assertEquals("claude-opus-4-8", obs.model)
        assertEquals(3, obs.remainingTurns)
        assertEquals(1, obs.subagents.size)
        assertEquals(1, obs.indexTools.size)
        assertEquals(4000, obs.primerCacheReadTokens)
    }
}
