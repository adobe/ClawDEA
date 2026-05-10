package com.adobe.clawdea.profiling.model

enum class Source {
    LIVE_INTELLIJ,
    LIVE_JFR,
    IMPORTED_JFR,
    IMPORTED_HPROF,
}

data class Recording(
    val source: Source,
    val timeRange: TimeRange,
    val cpuSamples: List<CpuSample>,
    val allocations: List<Allocation>,
    val heap: List<HeapObject>,
    val meta: Map<String, String>,
)
