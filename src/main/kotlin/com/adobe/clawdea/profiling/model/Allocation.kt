package com.adobe.clawdea.profiling.model

data class Allocation(
    val allocatedClass: String,
    val size: Long,
    val stack: List<Frame>,
    val timestampNs: Long,
    val isTlab: Boolean,
)
