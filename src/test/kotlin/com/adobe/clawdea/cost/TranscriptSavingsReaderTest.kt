package com.adobe.clawdea.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptSavingsReaderTest {

    @Test
    fun `countTopLevelTurns counts result lines`() {
        val lines = listOf(
            """{"type":"assistant","message":{"model":"claude-opus-4-8","usage":{"input_tokens":10}}}""",
            """{"type":"result","total_cost_usd":0.01}""",
            """{"type":"assistant","message":{"model":"claude-opus-4-8","usage":{"input_tokens":10}}}""",
            """{"type":"result","total_cost_usd":0.01}""",
        )
        assertEquals(2, TranscriptSavingsReader.countTopLevelTurns(lines))
    }

    @Test
    fun `isSubagentLine detects parentToolUseId`() {
        assertTrue(TranscriptSavingsReader.isSubagentLine("""{"parentToolUseId":"toolu_123","type":"assistant"}"""))
        assertEquals(false, TranscriptSavingsReader.isSubagentLine("""{"type":"assistant","message":{}}"""))
        assertEquals(false, TranscriptSavingsReader.isSubagentLine("""{"parentToolUseId":null,"type":"assistant"}"""))
    }

    @Test
    fun `reconstruct on empty transcript yields zero band and zero turns`() {
        val r = TranscriptSavingsReader.reconstruct(emptyList())
        assertEquals(SavingsBand.ZERO, r.band)
        assertEquals(0, r.turns)
    }

    @Test
    fun `reconstruct attributes remaining turns so a librarian turn nets positive in a long session`() {
        val lines = listOf(
            """{"type":"assistant","message":{"model":"claude-opus-4-8","usage":{"input_tokens":100}}}""",
            """{"parentToolUseId":"toolu_1","type":"assistant","message":{"model":"claude-opus-4-8","usage":{"input_tokens":35000}}}""",
            """{"type":"result","total_cost_usd":0.02}""",
            """{"type":"assistant","message":{"model":"claude-opus-4-8","usage":{"input_tokens":50}}}""",
            """{"type":"result","total_cost_usd":0.01}""",
            """{"type":"assistant","message":{"model":"claude-opus-4-8","usage":{"input_tokens":50}}}""",
            """{"type":"result","total_cost_usd":0.01}""",
            """{"type":"assistant","message":{"model":"claude-opus-4-8","usage":{"input_tokens":50}}}""",
            """{"type":"result","total_cost_usd":0.01}""",
        )
        val r = TranscriptSavingsReader.reconstruct(lines)
        assertEquals(4, r.turns)
        assertTrue("expected positive net, was ${r.band.expected}", r.band.expected > 0.0)
    }
}
