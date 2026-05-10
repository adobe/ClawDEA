package com.adobe.clawdea.profiling.analysis

import com.adobe.clawdea.profiling.model.Frame
import org.junit.Assert.*
import org.junit.Test

class SourceResolverTest {

    @Test
    fun `buildSourceLocation creates correct SourceLocation for in-project frame`() {
        val frame = Frame("com.adobe.clawdea.mcp.McpServer", "registerTools", 55, false)
        val loc = SourceResolver.buildSourceLocationFromFrame(frame, filePath = "src/main/kotlin/com/adobe/clawdea/mcp/McpServer.kt")
        assertEquals("com.adobe.clawdea.mcp.McpServer", loc.fqn)
        assertEquals("registerTools", loc.methodName)
        assertEquals(55, loc.startLine)
        assertEquals(55, loc.endLine)
        assertTrue(loc.inProject)
        assertNull(loc.versionHint)
    }

    @Test
    fun `buildSourceLocation marks as not-in-project when filePath is null`() {
        val frame = Frame("java.util.HashMap", "put", 500, false)
        val loc = SourceResolver.buildSourceLocationFromFrame(frame, filePath = null)
        assertFalse(loc.inProject)
        assertNull(loc.filePath)
    }

    @Test
    fun `buildSourceLocation includes versionHint when provided`() {
        val frame = Frame("com.example.App", "run", 42, false)
        val loc = SourceResolver.buildSourceLocationFromFrame(frame, filePath = "src/App.kt", versionHint = "line 42 doesn't match current source")
        assertNotNull(loc.versionHint)
        assertTrue(loc.versionHint!!.contains("doesn't match"))
    }
}
