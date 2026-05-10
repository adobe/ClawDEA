package com.adobe.clawdea.profiling.capture

import com.adobe.clawdea.profiling.capability.Capability
import com.adobe.clawdea.profiling.capability.ProfilerCapabilityProbe
import org.junit.Assert.*
import org.junit.Test

class CaptureServiceTest {

    @Test
    fun `selectBackend picks JFR when capability is JFR_ONLY`() {
        val probe = ProfilerCapabilityProbe(classChecker = { false })
        val selected = CaptureService.selectBackend(
            probe = probe,
            request = CaptureRequest(
                target = CaptureTarget.RunConfig("My App"),
                categories = setOf(Category.CPU),
            ),
        )
        assertEquals(BackendType.JFR, selected)
    }

    @Test
    fun `selectBackend picks IntelliJ when capability passes and categories supported`() {
        val probe = ProfilerCapabilityProbe(classChecker = { true })
        val selected = CaptureService.selectBackend(
            probe = probe,
            request = CaptureRequest(
                target = CaptureTarget.RunConfig("My App"),
                categories = setOf(Category.CPU, Category.ALLOCATIONS),
            ),
        )
        assertEquals(BackendType.INTELLIJ, selected)
    }

    @Test
    fun `selectBackend picks JFR for import targets regardless of capability`() {
        val probe = ProfilerCapabilityProbe(classChecker = { true })
        val selected = CaptureService.selectBackend(
            probe = probe,
            request = CaptureRequest(
                target = CaptureTarget.ImportFile("/path/to/file.jfr"),
                categories = setOf(Category.CPU),
            ),
        )
        assertEquals(BackendType.JFR, selected)
    }

    @Test
    fun `selectBackend picks JFR for HEAP_LEAK category even with IntelliJ available`() {
        val probe = ProfilerCapabilityProbe(classChecker = { true })
        val selected = CaptureService.selectBackend(
            probe = probe,
            request = CaptureRequest(
                target = CaptureTarget.RunConfig("My App"),
                categories = setOf(Category.HEAP_LEAK),
            ),
        )
        assertEquals(BackendType.JFR, selected)
    }

    @Test
    fun `selectBackend respects forced backend override`() {
        val probe = ProfilerCapabilityProbe(classChecker = { true })
        val selected = CaptureService.selectBackend(
            probe = probe,
            request = CaptureRequest(
                target = CaptureTarget.RunConfig("My App"),
                categories = setOf(Category.CPU),
                forcedBackend = BackendType.JFR,
            ),
        )
        assertEquals(BackendType.JFR, selected)
    }
}
