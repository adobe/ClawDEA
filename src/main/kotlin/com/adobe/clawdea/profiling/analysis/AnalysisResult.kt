package com.adobe.clawdea.profiling.analysis

import com.adobe.clawdea.profiling.model.Frame

data class SourceLocation(
    val fqn: String,
    val methodName: String,
    val filePath: String?,
    val startLine: Int,
    val endLine: Int,
    val inProject: Boolean,
    val versionHint: String?,
)

data class HotFrame(
    val frame: Frame,
    val selfCount: Int,
    val totalCount: Int,
    val selfPercent: Double,
    val sourceLocation: SourceLocation? = null,
)

data class HotStack(
    val stack: List<Frame>,
    val count: Int,
)

data class CpuHotspotResult(
    val totalSamples: Int,
    val wallClockDurationMs: Long,
    val topFrames: List<HotFrame>,
    val topCallStacks: List<HotStack>,
    val threadBreakdown: Map<String, Int>,
)

data class AllocationSite(
    val stack: List<Frame>,
    val allocatedClass: String,
    val totalBytes: Long,
    val count: Int,
    val sourceLocation: SourceLocation? = null,
)

data class AllocatedClass(
    val className: String,
    val totalBytes: Long,
    val count: Int,
)

data class AllocationHotspotResult(
    val totalBytesAllocated: Long,
    val topAllocators: List<AllocationSite>,
    val classBreakdown: List<AllocatedClass>,
)

data class LeakCandidate(
    val className: String,
    val instanceCount: Int,
    val totalRetainedBytes: Long,
    val sampleRootPath: List<String>,
)

data class HeapLeakResult(
    val totalRetainedBytes: Long,
    val topRetainers: List<LeakCandidate>,
)
