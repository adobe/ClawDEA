package com.adobe.clawdea.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `de-duplicates streamed copies of the same message id`() {
        val tmp = File.createTempFile("transcript", ".jsonl").apply { deleteOnExit() }
        // Claude Code rewrites the same assistant message multiple times while
        // streaming — same message.id, byte-identical usage. It must be priced once.
        // One turn: 1,000,000 output on opus ($25/M) = 25.0, regardless of copy count.
        val turn =
            """{"type":"assistant","message":{"id":"msg_abc","model":"claude-opus-4-8","usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":1000000}}}"""
        tmp.writeText("$turn\n$turn\n$turn\n$turn")
        assertEquals(25.0, TranscriptCostReader.sumCost(tmp), 1e-6)
    }

    @Test
    fun `readResumeCost reports total and last model`() {
        val tmp = File.createTempFile("transcript", ".jsonl").apply { deleteOnExit() }
        tmp.writeText(
            """
            {"type":"assistant","message":{"id":"msg_1","model":"claude-opus-4-8","usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":1000000}}}
            {"type":"assistant","message":{"id":"msg_2","model":"claude-sonnet-4-6","usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":1000000}}}
            """.trimIndent(),
        )
        // opus 1M out = 25.0; sonnet 1M out = 15.0 → total 40.0, last model sonnet.
        val resume = TranscriptCostReader.readResumeCost(tmp)
        assertEquals(40.0, resume.totalUsd, 1e-6)
        assertEquals("claude-sonnet-4-6", resume.lastModel)
    }

    @Test
    fun `readResumeCost on missing file is zero and null model`() {
        val resume = TranscriptCostReader.readResumeCost(File("/no/such/file.jsonl"))
        assertEquals(0.0, resume.totalUsd, 0.0)
        assertNull(resume.lastModel)
    }

    @Test
    fun `path scheme mirrors SessionScanner`() {
        val f = TranscriptCostReader.sessionTranscriptFile("/Users/x/proj", "abc-123")
        assertTrue(f.path.endsWith("/.claude/projects/-Users-x-proj/abc-123.jsonl"))
    }
}
