package com.adobe.clawdea.profiling.capture

enum class Category {
    CPU,
    ALLOCATIONS,
    HEAP_LEAK,
}

enum class BackendType {
    INTELLIJ,
    JFR,
}

sealed interface CaptureTarget {
    data class RunConfig(val configName: String) : CaptureTarget
    data class TestMethod(val fqn: String) : CaptureTarget
    data class AttachPid(val pid: Long) : CaptureTarget
    data class ImportFile(val path: String) : CaptureTarget
}

data class CaptureRequest(
    val target: CaptureTarget,
    val categories: Set<Category> = setOf(Category.CPU, Category.ALLOCATIONS),
    val forcedBackend: BackendType? = null,
)

enum class SessionState {
    STARTING,
    RUNNING,
    STOPPING,
    DONE,
    FAILED,
}

interface CaptureBackend {
    val type: BackendType
    fun isAvailable(): Boolean
    fun supports(categories: Set<Category>): Boolean
}
