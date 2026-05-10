package com.adobe.clawdea.profiling.analysis

import com.adobe.clawdea.profiling.model.*
import org.junit.Assert.*
import org.junit.Test

class AllocationHotspotAnalyzerTest {

    private val frameA = Frame("com.example.Factory", "create", 15, false)
    private val frameB = Frame("com.example.Cache", "put", 30, false)

    @Test
    fun `ranks allocators by total bytes descending`() {
        val allocations = listOf(
            Allocation("byte[]", 1024, listOf(frameA), 1000L, true),
            Allocation("byte[]", 2048, listOf(frameA), 1010L, true),
            Allocation("java.lang.String", 512, listOf(frameB), 1020L, false),
        )
        val recording = recording(allocations)
        val result = AllocationHotspotAnalyzer.analyze(recording, topN = 10)

        assertEquals(3584L, result.totalBytesAllocated)
        assertEquals(frameA, result.topAllocators[0].stack[0])
        assertEquals(3072L, result.topAllocators[0].totalBytes)
        assertEquals(2, result.topAllocators[0].count)
    }

    @Test
    fun `class breakdown groups by allocated type`() {
        val allocations = listOf(
            Allocation("byte[]", 1024, listOf(frameA), 1000L, true),
            Allocation("byte[]", 2048, listOf(frameB), 1010L, true),
            Allocation("java.lang.String", 512, listOf(frameA), 1020L, false),
        )
        val recording = recording(allocations)
        val result = AllocationHotspotAnalyzer.analyze(recording, topN = 10)

        val byteClass = result.classBreakdown.find { it.className == "byte[]" }!!
        assertEquals(3072L, byteClass.totalBytes)
        assertEquals(2, byteClass.count)
    }

    @Test
    fun `respects topN limit`() {
        val allocations = (1..10).map {
            Allocation("Class$it", it.toLong() * 100, listOf(frameA), 1000L + it, true)
        }
        val recording = recording(allocations)
        val result = AllocationHotspotAnalyzer.analyze(recording, topN = 3)

        assertEquals(3, result.topAllocators.size)
    }

    @Test
    fun `class filter limits to specified class`() {
        val allocations = listOf(
            Allocation("byte[]", 1024, listOf(frameA), 1000L, true),
            Allocation("java.lang.String", 512, listOf(frameB), 1010L, false),
        )
        val recording = recording(allocations)
        val result = AllocationHotspotAnalyzer.analyze(recording, topN = 10, classFilter = "byte[]")

        assertEquals(1024L, result.totalBytesAllocated)
        assertEquals(1, result.topAllocators.size)
    }

    private fun recording(allocations: List<Allocation>) = Recording(
        source = Source.IMPORTED_JFR,
        timeRange = TimeRange(1000L, 2000L),
        cpuSamples = emptyList(),
        allocations = allocations,
        heap = emptyList(),
        meta = emptyMap(),
    )
}
