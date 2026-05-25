/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.buildtool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GradleBuildToolTest {

    private fun fakeSupport(id: String, displayName: String = id) =
        fakeLanguageSupport(id, displayName)

    @Test fun `id is gradle`() {
        assertEquals("gradle", GradleBuildTool.id)
    }

    @Test fun `displayName is Gradle`() {
        assertEquals("Gradle", GradleBuildTool.displayName)
    }

    @Test fun `compileCommandFor Java returns gradlew compileJava quiet`() {
        val cmd = GradleBuildTool.compileCommandFor(fakeSupport("java", "Java"), stubProject("/proj"))
        assertNotNull(cmd)
        assertEquals(listOf("./gradlew", "compileJava", "--quiet"), cmd!!.argv)
        assertEquals(File("/proj"), cmd.workingDir)
    }

    @Test fun `compileCommandFor Kotlin returns gradlew compileKotlin quiet`() {
        val cmd = GradleBuildTool.compileCommandFor(fakeSupport("kotlin", "Kotlin"), stubProject("/proj"))
        assertNotNull(cmd)
        assertEquals(listOf("./gradlew", "compileKotlin", "--quiet"), cmd!!.argv)
    }

    @Test fun `compileCommandFor Scala returns gradlew compileScala quiet`() {
        val cmd = GradleBuildTool.compileCommandFor(fakeSupport("scala", "Scala"), stubProject("/proj"))
        assertNotNull(cmd)
        assertEquals(listOf("./gradlew", "compileScala", "--quiet"), cmd!!.argv)
    }

    @Test fun `compileCommandFor unknown language returns null`() {
        val cmd = GradleBuildTool.compileCommandFor(fakeSupport("xyz", "XYZ"), stubProject("/proj"))
        assertNull(cmd)
    }

    @Test fun `filterDiagnostics keeps lines containing the relative path`() {
        val output = """
            > Task :compileJava
            /proj/src/main/java/Foo.java:42: error: cannot find symbol
            > Task :compileJava FAILED
        """.trimIndent()
        val filtered = GradleBuildTool.filterDiagnostics(
            output, "/proj/src/main/java/Foo.java", "/proj",
        )
        assertTrue(
            "Filtered output should contain the error line, was: <$filtered>",
            filtered.contains("cannot find symbol"),
        )
    }

    @Test fun `filterDiagnostics keeps lines containing the absolute path`() {
        val output = """
            unrelated build output
            /absolute/Bar.java:10: warning: deprecated API
            more unrelated output
        """.trimIndent()
        val filtered = GradleBuildTool.filterDiagnostics(
            output, "/absolute/Bar.java", "/elsewhere",
        )
        assertTrue(filtered.contains("deprecated API"))
    }

    @Test fun `filterDiagnostics returns blank when no match`() {
        val output = "totally unrelated build output\nmore noise"
        val filtered = GradleBuildTool.filterDiagnostics(output, "/proj/Foo.java", "/proj")
        assertTrue(
            "Expected blank filter result, got: <$filtered>",
            filtered.isBlank(),
        )
    }
}
