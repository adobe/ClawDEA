package com.adobe.clawdea.profiling.analysis

import com.adobe.clawdea.profiling.model.*
import org.junit.Assert.*
import org.junit.Test

class CpuHotspotAnalyzerTest {

    private val frameA = Frame("com.example.Service", "process", 10, false)
    private val frameB = Frame("com.example.Service", "validate", 20, false)
    private val frameC = Frame("com.example.Util", "hash", 5, false)

    @Test
    fun `ranks frames by self-time descending`() {
        val samples = listOf(
            CpuSample(listOf(frameA), "main", 1000L, "RUNNABLE"),
            CpuSample(listOf(frameA), "main", 1010L, "RUNNABLE"),
            CpuSample(listOf(frameA), "main", 1020L, "RUNNABLE"),
            CpuSample(listOf(frameB), "main", 1030L, "RUNNABLE"),
        )
        val recording = recording(cpuSamples = samples)
        val result = CpuHotspotAnalyzer.analyze(recording, topN = 10)

        assertEquals(4, result.totalSamples)
        assertEquals(frameA, result.topFrames[0].frame)
        assertEquals(3, result.topFrames[0].selfCount)
        assertEquals(frameB, result.topFrames[1].frame)
        assertEquals(1, result.topFrames[1].selfCount)
    }

    @Test
    fun `respects topN limit`() {
        val samples = listOf(
            CpuSample(listOf(frameA), "main", 1000L, "RUNNABLE"),
            CpuSample(listOf(frameB), "main", 1010L, "RUNNABLE"),
            CpuSample(listOf(frameC), "main", 1020L, "RUNNABLE"),
        )
        val recording = recording(cpuSamples = samples)
        val result = CpuHotspotAnalyzer.analyze(recording, topN = 2)

        assertEquals(2, result.topFrames.size)
    }

    @Test
    fun `computes thread breakdown`() {
        val samples = listOf(
            CpuSample(listOf(frameA), "worker-1", 1000L, "RUNNABLE"),
            CpuSample(listOf(frameA), "worker-1", 1010L, "RUNNABLE"),
            CpuSample(listOf(frameA), "worker-2", 1020L, "RUNNABLE"),
        )
        val recording = recording(cpuSamples = samples)
        val result = CpuHotspotAnalyzer.analyze(recording, topN = 10)

        assertEquals(2, result.threadBreakdown["worker-1"])
        assertEquals(1, result.threadBreakdown["worker-2"])
    }

    @Test
    fun `thread filter limits to specified thread`() {
        val samples = listOf(
            CpuSample(listOf(frameA), "worker-1", 1000L, "RUNNABLE"),
            CpuSample(listOf(frameB), "worker-2", 1010L, "RUNNABLE"),
        )
        val recording = recording(cpuSamples = samples)
        val result = CpuHotspotAnalyzer.analyze(recording, topN = 10, threadFilter = "worker-1")

        assertEquals(1, result.totalSamples)
        assertEquals(frameA, result.topFrames[0].frame)
    }

    @Test
    fun `empty recording returns zero totals`() {
        val recording = recording(cpuSamples = emptyList())
        val result = CpuHotspotAnalyzer.analyze(recording, topN = 10)

        assertEquals(0, result.totalSamples)
        assertTrue(result.topFrames.isEmpty())
    }

    private fun recording(cpuSamples: List<CpuSample>) = Recording(
        source = Source.IMPORTED_JFR,
        timeRange = TimeRange(1000L, 2000L),
        cpuSamples = cpuSamples,
        allocations = emptyList(),
        heap = emptyList(),
        meta = emptyMap(),
    )
}
