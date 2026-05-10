package com.adobe.clawdea.profiling.mcp

import com.adobe.clawdea.mcp.McpToolRouter
import com.adobe.clawdea.profiling.analysis.*
import com.adobe.clawdea.profiling.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class McpProfilingToolsTest {

    private lateinit var router: McpToolRouter

    @Before
    fun setUp() {
        router = McpToolRouter()
        val analysisService = AnalysisService()
        // Seed a test recording
        val recording = Recording(
            source = Source.IMPORTED_JFR,
            timeRange = TimeRange(0L, 1_000_000_000L),
            cpuSamples = listOf(
                CpuSample(listOf(Frame("com.App", "run", 10, false)), "main", 100L, "RUNNABLE"),
            ),
            allocations = listOf(
                Allocation("byte[]", 1024, listOf(Frame("com.App", "alloc", 20, false)), 200L, true),
            ),
            heap = emptyList(),
            meta = mapOf("jvm.version" to "21"),
        )
        analysisService.register("test-123", recording)

        McpProfilingTools(analysisService).registerAll(router)
    }

    @Test
    fun `registers all 8 tools`() {
        val json = router.toolsListJson()
        assertTrue(json.contains("profiling_start"))
        assertTrue(json.contains("profiling_stop"))
        assertTrue(json.contains("profiling_status"))
        assertTrue(json.contains("profiling_list"))
        assertTrue(json.contains("profiling_import"))
        assertTrue(json.contains("profiling_analyze_cpu"))
        assertTrue(json.contains("profiling_analyze_allocations"))
        assertTrue(json.contains("profiling_analyze_leaks"))
    }

    @Test
    fun `profiling_analyze_cpu returns JSON with topFrames`() {
        val result = router.dispatch("profiling_analyze_cpu", mapOf("recording_id" to "test-123"))
        assertFalse(result.isError)
        assertTrue(result.text.contains("topFrames"))
        assertTrue(result.text.contains("com.App"))
        assertTrue(result.text.contains("totalSamples"))
    }

    @Test
    fun `profiling_analyze_allocations returns JSON with topAllocators`() {
        val result = router.dispatch("profiling_analyze_allocations", mapOf("recording_id" to "test-123"))
        assertFalse(result.isError)
        assertTrue(result.text.contains("topAllocators"))
        assertTrue(result.text.contains("byte[]"))
    }

    @Test
    fun `profiling_analyze_cpu errors on unknown recording_id`() {
        val result = router.dispatch("profiling_analyze_cpu", mapOf("recording_id" to "nonexistent"))
        assertTrue(result.isError)
        assertTrue(result.text.contains("not found"))
    }

    @Test
    fun `profiling_analyze_leaks errors on non-hprof recording`() {
        val result = router.dispatch("profiling_analyze_leaks", mapOf("recording_id" to "test-123"))
        assertTrue(result.isError)
        assertTrue(result.text.contains("hprof"))
    }

    @Test
    fun `profiling_list returns valid JSON`() {
        val result = router.dispatch("profiling_list", emptyMap())
        assertFalse(result.isError)
        assertTrue(result.text.contains("test-123"))
    }
}
