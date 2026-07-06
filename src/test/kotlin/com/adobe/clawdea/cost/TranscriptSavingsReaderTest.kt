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
        assertTrue(TranscriptSavingsReader.isSubagentLine("""{"parent_tool_use_id":"toolu_123","type":"assistant"}"""))
        assertEquals(false, TranscriptSavingsReader.isSubagentLine("""{"type":"assistant","message":{}}"""))
        assertEquals(false, TranscriptSavingsReader.isSubagentLine("""{"parentToolUseId":null,"type":"assistant"}"""))
        assertEquals(false, TranscriptSavingsReader.isSubagentLine("""{"parent_tool_use_id":null,"type":"assistant"}"""))
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

    @Test
    fun `duplicate streamed result lines with same message id count as one turn`() {
        // Claude Code rewrites the same logical message up to ~4x; same message.id must count once.
        val dup = """{"type":"result","message":{"id":"msg_1"},"total_cost_usd":0.01}"""
        val lines = listOf(
            """{"type":"assistant","message":{"id":"msg_a","model":"claude-opus-4-8","usage":{"input_tokens":10}}}""",
            dup, dup, dup,
        )
        assertEquals(1, TranscriptSavingsReader.countTopLevelTurns(lines))
    }

    @Test
    fun `result lines without a message id each count once`() {
        // Fixture-style lines with no message.id fall back to counting once each (no dedup).
        val lines = listOf(
            """{"type":"result","total_cost_usd":0.01}""",
            """{"type":"result","total_cost_usd":0.01}""",
        )
        assertEquals(2, TranscriptSavingsReader.countTopLevelTurns(lines))
    }

    @Test
    fun `duplicate subagent lines with same message id are not double counted`() {
        val subDup = """{"parentToolUseId":"toolu_1","type":"assistant","message":{"id":"msg_sub","model":"claude-opus-4-8","usage":{"input_tokens":20000}}}"""
        val linesDup = listOf(
            """{"type":"assistant","message":{"id":"msg_top","model":"claude-opus-4-8","usage":{"input_tokens":50}}}""",
            subDup, subDup, subDup,
            """{"type":"result","message":{"id":"msg_r1"},"total_cost_usd":0.02}""",
            """{"type":"assistant","message":{"id":"msg_t2","model":"claude-opus-4-8","usage":{"input_tokens":50}}}""",
            """{"type":"result","message":{"id":"msg_r2"},"total_cost_usd":0.01}""",
        )
        val linesSingle = listOf(
            """{"type":"assistant","message":{"id":"msg_top","model":"claude-opus-4-8","usage":{"input_tokens":50}}}""",
            subDup,
            """{"type":"result","message":{"id":"msg_r1"},"total_cost_usd":0.02}""",
            """{"type":"assistant","message":{"id":"msg_t2","model":"claude-opus-4-8","usage":{"input_tokens":50}}}""",
            """{"type":"result","message":{"id":"msg_r2"},"total_cost_usd":0.01}""",
        )
        // The triple-written subagent must yield the SAME band as the single-written one.
        assertEquals(
            TranscriptSavingsReader.reconstruct(linesSingle).band.expected,
            TranscriptSavingsReader.reconstruct(linesDup).band.expected,
            1e-9,
        )
        assertEquals(2, TranscriptSavingsReader.reconstruct(linesDup).turns)
    }

    @Test
    fun `reconstruct attributes index tool savings from tool results`() {
        val lines = listOf(
            """{"type":"assistant","message":{"model":"claude-opus-4-8","content":[{"type":"tool_use","id":"tu_1","name":"mcp__clawdea-intellij__find_usages","input":{}}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tu_1","content":"${"x".repeat(400)}"}]}}""",
            """{"type":"result","total_cost_usd":0.01}""",
        )
        val r = TranscriptSavingsReader.reconstruct(lines)
        val indexBand = r.leverBands[LeverId.INDEX_TOOLS] ?: SavingsBand.ZERO
        assert(indexBand.expected > 0.0) { "expected index-tool savings, was ${indexBand.expected}" }
    }
}
