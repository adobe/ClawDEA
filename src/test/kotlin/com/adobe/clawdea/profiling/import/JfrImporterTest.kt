package com.adobe.clawdea.profiling.`import`

import com.adobe.clawdea.profiling.model.Source
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import jdk.jfr.*

class JfrImporterTest {

    @Test
    fun `imports CPU samples from a JFR recording`() {
        val path = createTestRecording(withCpu = true, withAlloc = false)
        try {
            val recording = JfrImporter.import(path)
            assertEquals(Source.IMPORTED_JFR, recording.source)
            assertTrue(recording.cpuSamples.isNotEmpty())
            assertTrue(recording.allocations.isEmpty())
            assertTrue(recording.heap.isEmpty())
            assertTrue(recording.timeRange.startNs > 0)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `imports allocation events from a JFR recording`() {
        val path = createTestRecording(withCpu = false, withAlloc = true)
        try {
            val recording = JfrImporter.import(path)
            assertTrue(recording.allocations.isNotEmpty())
            val first = recording.allocations[0]
            assertTrue(first.size > 0)
            assertTrue(first.allocatedClass.isNotEmpty())
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `empty recording returns Recording with empty lists`() {
        val path = createEmptyRecording()
        try {
            val recording = JfrImporter.import(path)
            assertTrue(recording.cpuSamples.isEmpty())
            assertTrue(recording.allocations.isEmpty())
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun createTestRecording(withCpu: Boolean, withAlloc: Boolean): Path {
        val path = Files.createTempFile("clawdea-test-", ".jfr")
        val recording = Recording()
        if (withCpu) recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1))
        if (withAlloc) recording.enable("jdk.ObjectAllocationInNewTLAB")
        recording.start()
        // Generate sustained CPU work to produce samples
        val deadline = System.nanoTime() + 500_000_000L // 500ms
        var sum = 0.0
        while (System.nanoTime() < deadline) {
            repeat(10_000) { i -> sum += Math.sin(i.toDouble()) }
        }
        // Also allocate to trigger TLAB events
        val list = mutableListOf<ByteArray>()
        repeat(5000) { list.add(ByteArray(4096)) }
        recording.stop()
        recording.dump(path)
        recording.close()
        return path
    }

    private fun createEmptyRecording(): Path {
        val path = Files.createTempFile("clawdea-test-empty-", ".jfr")
        val recording = Recording()
        recording.start()
        recording.stop()
        recording.dump(path)
        recording.close()
        return path
    }
}
