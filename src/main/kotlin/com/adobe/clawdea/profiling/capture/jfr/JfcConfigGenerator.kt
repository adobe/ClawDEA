package com.adobe.clawdea.profiling.capture.jfr

import com.adobe.clawdea.profiling.capture.Category

object JfcConfigGenerator {

    fun generate(categories: Set<Category>, samplingIntervalMs: Int): String {
        val events = mutableListOf<String>()

        if (Category.CPU in categories) {
            events += event("jdk.CPUTimeSample", mapOf("enabled" to "true", "period" to "$samplingIntervalMs ms"))
            events += event("jdk.CPUSample", mapOf("enabled" to "true", "period" to "$samplingIntervalMs ms"))
        }

        if (Category.ALLOCATIONS in categories) {
            events += event("jdk.ObjectAllocationInNewTLAB", mapOf("enabled" to "true", "stackTrace" to "true"))
            events += event("jdk.ObjectAllocationOutsideTLAB", mapOf("enabled" to "true", "stackTrace" to "true"))
        }

        // Always-on context events (cheap, useful for orientation)
        events += event("jdk.GarbageCollection", mapOf("enabled" to "true"))
        events += event("jdk.ThreadDump", mapOf("enabled" to "true", "period" to "60 s"))

        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<configuration version="2.0" label="ClawDEA Profiling" provider="ClawDEA">""")
            events.forEach { appendLine(it) }
            appendLine("</configuration>")
        }
    }

    private fun event(name: String, settings: Map<String, String>): String {
        val settingsXml = settings.entries.joinToString("\n        ") { (k, v) ->
            """<setting name="$k">$v</setting>"""
        }
        return """    <event name="$name">
        $settingsXml
    </event>"""
    }
}
