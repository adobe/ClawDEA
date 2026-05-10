package com.adobe.clawdea.profiling.capability

enum class Capability {
    INTELLIJ_OR_JFR,
    JFR_ONLY,
}

class ProfilerCapabilityProbe(
    private val classChecker: (String) -> Boolean = { className ->
        try {
            Class.forName(className)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    },
) {
    @Volatile
    private var cached: Capability? = null

    fun probe(): Capability {
        cached?.let { return it }
        val result = runProbe()
        cached = result
        return result
    }

    fun downgrade() {
        cached = Capability.JFR_ONLY
    }

    private fun runProbe(): Capability {
        val present = classChecker("com.intellij.profiler.api.ProfilerData")
        return if (present) Capability.INTELLIJ_OR_JFR else Capability.JFR_ONLY
    }
}
