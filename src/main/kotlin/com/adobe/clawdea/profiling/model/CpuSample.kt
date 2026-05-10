package com.adobe.clawdea.profiling.model

data class CpuSample(
    val stack: List<Frame>,
    val threadName: String,
    val timestampNs: Long,
    val state: String,
)
