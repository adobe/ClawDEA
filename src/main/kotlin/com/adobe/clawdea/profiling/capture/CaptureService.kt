package com.adobe.clawdea.profiling.capture

import com.adobe.clawdea.profiling.capability.Capability
import com.adobe.clawdea.profiling.capability.ProfilerCapabilityProbe

class CaptureService {
    companion object {
        private val INTELLIJ_SUPPORTED = setOf(Category.CPU, Category.ALLOCATIONS)

        fun selectBackend(probe: ProfilerCapabilityProbe, request: CaptureRequest): BackendType {
            if (request.forcedBackend != null) return request.forcedBackend
            if (request.target is CaptureTarget.ImportFile) return BackendType.JFR
            if (probe.probe() == Capability.JFR_ONLY) return BackendType.JFR
            if (!INTELLIJ_SUPPORTED.containsAll(request.categories)) return BackendType.JFR
            return BackendType.INTELLIJ
        }
    }
}
