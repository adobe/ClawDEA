package com.adobe.clawdea.profiling.capability

import org.junit.Assert.*
import org.junit.Test

class ProfilerCapabilityProbeTest {

    @Test
    fun `probe returns JFR_ONLY when profiler plugin class not found`() {
        val probe = ProfilerCapabilityProbe(
            classChecker = { false },
        )
        val result = probe.probe()
        assertEquals(Capability.JFR_ONLY, result)
    }

    @Test
    fun `probe returns INTELLIJ_OR_JFR when all gates pass`() {
        val probe = ProfilerCapabilityProbe(
            classChecker = { true },
        )
        val result = probe.probe()
        assertEquals(Capability.INTELLIJ_OR_JFR, result)
    }

    @Test
    fun `probe caches result across calls`() {
        var callCount = 0
        val probe = ProfilerCapabilityProbe(
            classChecker = { callCount++; true },
        )
        probe.probe()
        probe.probe()
        assertEquals(1, callCount)
    }

    @Test
    fun `downgrade sets capability to JFR_ONLY`() {
        val probe = ProfilerCapabilityProbe(
            classChecker = { true },
        )
        assertEquals(Capability.INTELLIJ_OR_JFR, probe.probe())
        probe.downgrade()
        assertEquals(Capability.JFR_ONLY, probe.probe())
    }
}
