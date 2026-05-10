package com.adobe.clawdea.profiling.`import`

import com.adobe.clawdea.profiling.model.*
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedStackTrace
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Path

object JfrImporter {

    fun import(path: Path): Recording {
        val cpuSamples = mutableListOf<CpuSample>()
        val allocations = mutableListOf<Allocation>()
        var minTimestamp = Long.MAX_VALUE
        var maxTimestamp = Long.MIN_VALUE

        RecordingFile(path).use { rf ->
            while (rf.hasMoreEvents()) {
                val event = rf.readEvent()
                val ts = event.startTime.toEpochMilli() * 1_000_000
                if (ts < minTimestamp) minTimestamp = ts
                if (ts > maxTimestamp) maxTimestamp = ts

                when (event.eventType.name) {
                    "jdk.CPUTimeSample", "jdk.CPUSample",
                    "jdk.ExecutionSample", "jdk.NativeMethodSample" -> {
                        cpuSamples += toCpuSample(event, ts)
                    }
                    "jdk.ObjectAllocationInNewTLAB" -> {
                        allocations += toAllocation(event, ts, isTlab = true)
                    }
                    "jdk.ObjectAllocationOutsideTLAB" -> {
                        allocations += toAllocation(event, ts, isTlab = false)
                    }
                }
            }
        }

        if (minTimestamp == Long.MAX_VALUE) {
            minTimestamp = 0L
            maxTimestamp = 0L
        }

        return Recording(
            source = Source.IMPORTED_JFR,
            timeRange = TimeRange(minTimestamp, maxTimestamp),
            cpuSamples = cpuSamples,
            allocations = allocations,
            heap = emptyList(),
            meta = emptyMap(),
        )
    }

    private fun toCpuSample(event: RecordedEvent, timestampNs: Long): CpuSample {
        val stack = extractStack(event.stackTrace)
        val threadName = event.thread?.javaName ?: "unknown"
        val state = tryGetString(event, "state") ?: "UNKNOWN"
        return CpuSample(stack, threadName, timestampNs, state)
    }

    private fun toAllocation(event: RecordedEvent, timestampNs: Long, isTlab: Boolean): Allocation {
        val stack = extractStack(event.stackTrace)
        val className = event.getClass("objectClass")?.name ?: "unknown"
        val size = tryGetLong(event, "allocationSize") ?: tryGetLong(event, "tlabSize") ?: 0L
        return Allocation(className, size, stack, timestampNs, isTlab)
    }

    private fun extractStack(trace: RecordedStackTrace?): List<Frame> {
        if (trace == null) return emptyList()
        return trace.frames.map { f ->
            Frame(
                className = f.method.type.name,
                methodName = f.method.name,
                lineNumber = f.lineNumber,
                isNative = f.isJavaFrame.not(),
            )
        }
    }

    private fun tryGetString(event: RecordedEvent, field: String): String? =
        try { event.getString(field) } catch (_: Exception) { null }

    private fun tryGetLong(event: RecordedEvent, field: String): Long? =
        try { event.getLong(field) } catch (_: Exception) { null }
}
