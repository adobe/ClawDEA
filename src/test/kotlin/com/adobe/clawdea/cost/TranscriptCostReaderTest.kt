package com.adobe.clawdea.cost

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TranscriptCostReaderTest {

    @Test
    fun `sums total_cost_usd across result lines`() {
        val tmp = File.createTempFile("transcript", ".jsonl").apply { deleteOnExit() }
        tmp.writeText(
            """
            {"type":"user","message":{"content":"hi"}}
            {"type":"result","total_cost_usd":0.0123,"session_id":"s"}
            {"type":"assistant","message":{"content":[{"type":"text","text":"hello"}]}}
            {"type":"result","total_cost_usd":0.0098,"session_id":"s"}
            """.trimIndent(),
        )
        assertEquals(0.0221, TranscriptCostReader.sumCost(tmp), 1e-9)
    }

    @Test
    fun `missing file yields zero`() {
        assertEquals(0.0, TranscriptCostReader.sumCost(File("/no/such/file.jsonl")), 0.0)
    }

    @Test
    fun `path scheme mirrors SessionScanner`() {
        val f = TranscriptCostReader.sessionTranscriptFile("/Users/x/proj", "abc-123")
        assertTrue(f.path.endsWith("/.claude/projects/-Users-x-proj/abc-123.jsonl"))
    }
}
