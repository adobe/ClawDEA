package com.adobe.clawdea.profiling.analysis

import com.adobe.clawdea.profiling.model.Frame
import com.adobe.clawdea.profiling.model.Recording

object CpuHotspotAnalyzer {

    fun analyze(recording: Recording, topN: Int, threadFilter: String? = null): CpuHotspotResult {
        val samples = if (threadFilter != null) {
            recording.cpuSamples.filter { it.threadName == threadFilter }
        } else {
            recording.cpuSamples
        }

        if (samples.isEmpty()) {
            return CpuHotspotResult(
                totalSamples = 0,
                wallClockDurationMs = recording.timeRange.durationMs,
                topFrames = emptyList(),
                topCallStacks = emptyList(),
                threadBreakdown = emptyMap(),
            )
        }

        val selfCounts = mutableMapOf<Frame, Int>()
        val totalCounts = mutableMapOf<Frame, Int>()
        val threadCounts = mutableMapOf<String, Int>()
        val stackCounts = mutableMapOf<List<Frame>, Int>()

        for (sample in samples) {
            threadCounts.merge(sample.threadName, 1, Int::plus)

            if (sample.stack.isNotEmpty()) {
                val topFrame = sample.stack[0]
                selfCounts.merge(topFrame, 1, Int::plus)
                stackCounts.merge(sample.stack, 1, Int::plus)
            }

            for (frame in sample.stack.toSet()) {
                totalCounts.merge(frame, 1, Int::plus)
            }
        }

        val totalSamples = samples.size
        val topFrames = selfCounts.entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { (frame, selfCount) ->
                HotFrame(
                    frame = frame,
                    selfCount = selfCount,
                    totalCount = totalCounts[frame] ?: selfCount,
                    selfPercent = selfCount.toDouble() / totalSamples * 100.0,
                )
            }

        val topStacks = stackCounts.entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { (stack, count) -> HotStack(stack, count) }

        return CpuHotspotResult(
            totalSamples = totalSamples,
            wallClockDurationMs = recording.timeRange.durationMs,
            topFrames = topFrames,
            topCallStacks = topStacks,
            threadBreakdown = threadCounts,
        )
    }
}
