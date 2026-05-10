package com.adobe.clawdea.profiling.model

data class TimeRange(val startNs: Long, val endNs: Long) {
    val durationMs: Long get() = (endNs - startNs) / 1_000_000
}
