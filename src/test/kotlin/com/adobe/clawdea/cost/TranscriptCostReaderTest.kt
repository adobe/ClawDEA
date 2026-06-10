package com.adobe.clawdea.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TranscriptCostReaderTest {

    @Test
    fun `prices assistant usage lines and sums`() {
        val tmp = File.createTempFile("transcript", ".jsonl").apply { deleteOnExit() }
        // Two assistant turns on opus (input $5/M, output $25/M, cache-read 0.1x input).
        // Turn 1: 1,000,000 input + 1,000,000 output = 5 + 25 = 30.0
        // Turn 2: 1,000,000 cache_read = 5 * 0.1 = 0.5
        // user/other lines must be ignored.
        tmp.writeText(
            """
            {"type":"user","message":{"role":"user","content":"hi"}}
            {"type":"assistant","message":{"model":"claude-opus-4-8","role":"assistant","usage":{"input_tokens":1000000,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":1000000}}}
            {"type":"assistant","message":{"model":"claude-opus-4-8","role":"assistant","usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":1000000,"output_tokens":0}}}
            """.trimIndent(),
        )
        assertEquals(30.5, TranscriptCostReader.sumCost(tmp), 1e-6)
    }

    @Test
    fun `missing file yields zero`() {
        assertEquals(0.0, TranscriptCostReader.sumCost(File("/no/such/file.jsonl")), 0.0)
    }

    @Test
    fun `lines without usage are ignored`() {
        val tmp = File.createTempFile("transcript", ".jsonl").apply { deleteOnExit() }
        tmp.writeText(
            """
            {"type":"system","subtype":"init"}
            {"type":"assistant","message":{"model":"claude-opus-4-8","content":[{"type":"text","text":"hello"}]}}
            """.trimIndent(),
        )
        assertEquals(0.0, TranscriptCostReader.sumCost(tmp), 1e-9)
    }

    @Test
    fun `path scheme mirrors SessionScanner`() {
        val f = TranscriptCostReader.sessionTranscriptFile("/Users/x/proj", "abc-123")
        assertTrue(f.path.endsWith("/.claude/projects/-Users-x-proj/abc-123.jsonl"))
    }
}
