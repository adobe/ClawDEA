package com.adobe.clawdea.profiling.analysis

import com.adobe.clawdea.profiling.model.Frame
import com.adobe.clawdea.profiling.model.Recording

object AllocationHotspotAnalyzer {

    fun analyze(recording: Recording, topN: Int, classFilter: String? = null): AllocationHotspotResult {
        val allocations = if (classFilter != null) {
            recording.allocations.filter { it.allocatedClass == classFilter }
        } else {
            recording.allocations
        }

        if (allocations.isEmpty()) {
            return AllocationHotspotResult(
                totalBytesAllocated = 0,
                topAllocators = emptyList(),
                classBreakdown = emptyList(),
            )
        }

        val totalBytes = allocations.sumOf { it.size }

        // Group by top frame (allocation site) and allocated class
        data class SiteKey(val topFrame: Frame, val allocatedClass: String)

        val siteAgg = mutableMapOf<SiteKey, Pair<Long, Int>>()
        for (alloc in allocations) {
            val key = SiteKey(
                topFrame = alloc.stack.firstOrNull() ?: Frame("unknown", "unknown", 0, false),
                allocatedClass = alloc.allocatedClass,
            )
            val (prevBytes, prevCount) = siteAgg.getOrDefault(key, 0L to 0)
            siteAgg[key] = (prevBytes + alloc.size) to (prevCount + 1)
        }

        val topAllocators = siteAgg.entries
            .sortedByDescending { it.value.first }
            .take(topN)
            .map { (key, agg) ->
                AllocationSite(
                    stack = listOf(key.topFrame),
                    allocatedClass = key.allocatedClass,
                    totalBytes = agg.first,
                    count = agg.second,
                )
            }

        // Class breakdown
        val classAgg = mutableMapOf<String, Pair<Long, Int>>()
        for (alloc in allocations) {
            val (prevBytes, prevCount) = classAgg.getOrDefault(alloc.allocatedClass, 0L to 0)
            classAgg[alloc.allocatedClass] = (prevBytes + alloc.size) to (prevCount + 1)
        }

        val classBreakdown = classAgg.entries
            .sortedByDescending { it.value.first }
            .map { (className, agg) -> AllocatedClass(className, agg.first, agg.second) }

        return AllocationHotspotResult(
            totalBytesAllocated = totalBytes,
            topAllocators = topAllocators,
            classBreakdown = classBreakdown,
        )
    }
}
