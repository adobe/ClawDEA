package com.adobe.clawdea.profiling.settings

import com.adobe.clawdea.profiling.capture.BackendType
import com.adobe.clawdea.profiling.capture.jfr.ProfilingSessionSettings
import com.adobe.clawdea.settings.ClawDEASettings

object ProfilingSettings {

    fun sessionSettings(): ProfilingSessionSettings {
        val state = ClawDEASettings.getInstance().state
        return ProfilingSessionSettings(
            samplingIntervalMs = state.profilingSamplingIntervalMs,
            maxRecordingBytes = state.profilingMaxRecordingMb.toLong() * 1024 * 1024,
            maxDurationSeconds = state.profilingMaxDurationSeconds,
            stackDepth = state.profilingStackDepth,
        )
    }

    fun backendPreference(): BackendType? = when (ClawDEASettings.getInstance().state.profilingBackendPreference) {
        "intellij" -> BackendType.INTELLIJ
        "jfr" -> BackendType.JFR
        else -> null
    }

    fun maxRecordings(): Int = ClawDEASettings.getInstance().state.profilingMaxRecordings
    fun maxStorageBytes(): Long = ClawDEASettings.getInstance().state.profilingMaxStorageGb.toLong() * 1024 * 1024 * 1024
    fun autoAnalyze(): Boolean = ClawDEASettings.getInstance().state.profilingAutoAnalyze
    fun topN(): Int = ClawDEASettings.getInstance().state.profilingTopN
}
