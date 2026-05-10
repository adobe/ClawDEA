package com.adobe.clawdea.profiling.model

import org.junit.Assert.*
import org.junit.Test

class RecordingTest {

    @Test
    fun `Recording with CPU samples reports correct sample count`() {
        val frame = Frame("com.example.App", "main", 10, false)
        val samples = listOf(
            CpuSample(listOf(frame), "main", 1000L, "RUNNABLE"),
            CpuSample(listOf(frame), "main", 1010L, "RUNNABLE"),
        )
        val recording = Recording(
            source = Source.LIVE_JFR,
            timeRange = TimeRange(1000L, 2000L),
            cpuSamples = samples,
            allocations = emptyList(),
            heap = emptyList(),
            meta = mapOf("jvm.version" to "21.0.1"),
        )
        assertEquals(2, recording.cpuSamples.size)
        assertEquals(Source.LIVE_JFR, recording.source)
        assertEquals(1000L, recording.timeRange.startNs)
    }

    @Test
    fun `Recording with heap objects has empty CPU samples`() {
        val obj = HeapObject(
            id = 1L,
            className = "java.lang.String",
            shallowSize = 48,
            referenceIds = listOf(2L, 3L),
            isGcRoot = false,
        )
        val recording = Recording(
            source = Source.IMPORTED_HPROF,
            timeRange = TimeRange(0L, 0L),
            cpuSamples = emptyList(),
            allocations = emptyList(),
            heap = listOf(obj),
            meta = emptyMap(),
        )
        assertTrue(recording.cpuSamples.isEmpty())
        assertEquals(1, recording.heap.size)
        assertEquals("java.lang.String", recording.heap[0].className)
    }

    @Test
    fun `Frame equals by content`() {
        val a = Frame("com.example.Foo", "bar", 42, false)
        val b = Frame("com.example.Foo", "bar", 42, false)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Source enum has all four variants`() {
        val sources = Source.entries
        assertEquals(4, sources.size)
        assertTrue(sources.contains(Source.LIVE_INTELLIJ))
        assertTrue(sources.contains(Source.LIVE_JFR))
        assertTrue(sources.contains(Source.IMPORTED_JFR))
        assertTrue(sources.contains(Source.IMPORTED_HPROF))
    }
}
