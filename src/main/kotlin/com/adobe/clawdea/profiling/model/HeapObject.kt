package com.adobe.clawdea.profiling.model

data class HeapObject(
    val id: Long,
    val className: String,
    val shallowSize: Long,
    val referenceIds: List<Long>,
    val isGcRoot: Boolean,
)
