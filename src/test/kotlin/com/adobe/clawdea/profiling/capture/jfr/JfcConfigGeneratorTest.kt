package com.adobe.clawdea.profiling.capture.jfr

import com.adobe.clawdea.profiling.capture.Category
import org.junit.Assert.*
import org.junit.Test

class JfcConfigGeneratorTest {

    @Test
    fun `generates valid XML with CPU events enabled`() {
        val xml = JfcConfigGenerator.generate(
            categories = setOf(Category.CPU),
            samplingIntervalMs = 10,
        )
        assertTrue(xml.contains("jdk.CPUTimeSample"))
        assertTrue(xml.contains("<setting name=\"enabled\">true</setting>"))
        assertTrue(xml.contains("<setting name=\"period\">10 ms</setting>"))
    }

    @Test
    fun `generates XML with allocation events when ALLOCATIONS category`() {
        val xml = JfcConfigGenerator.generate(
            categories = setOf(Category.ALLOCATIONS),
            samplingIntervalMs = 10,
        )
        assertTrue(xml.contains("jdk.ObjectAllocationInNewTLAB"))
        assertTrue(xml.contains("jdk.ObjectAllocationOutsideTLAB"))
    }

    @Test
    fun `CPU events disabled when category not included`() {
        val xml = JfcConfigGenerator.generate(
            categories = setOf(Category.ALLOCATIONS),
            samplingIntervalMs = 10,
        )
        assertFalse(xml.contains("jdk.CPUTimeSample"))
    }

    @Test
    fun `always includes GC and ThreadDump as context events`() {
        val xml = JfcConfigGenerator.generate(
            categories = setOf(Category.CPU),
            samplingIntervalMs = 10,
        )
        assertTrue(xml.contains("jdk.GarbageCollection"))
        assertTrue(xml.contains("jdk.ThreadDump"))
    }

    @Test
    fun `XML is well-formed with configuration root element`() {
        val xml = JfcConfigGenerator.generate(
            categories = setOf(Category.CPU, Category.ALLOCATIONS),
            samplingIntervalMs = 20,
        )
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<configuration"))
        assertTrue(xml.contains("</configuration>"))
    }
}
