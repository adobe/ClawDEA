/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.buildtool

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class SbtBuildToolTest {

    private lateinit var tempDir: Path

    private fun fakeSupport(id: String, displayName: String = id) =
        fakeLanguageSupport(id, displayName)

    @Before fun setUp() {
        tempDir = Files.createTempDirectory("sbt-build-tool-test")
    }

    @After fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test fun `id is sbt`() {
        assertEquals("sbt", SbtBuildTool.id)
    }

    @Test fun `displayName is sbt`() {
        assertEquals("sbt", SbtBuildTool.displayName)
    }

    @Test fun `compileCommandFor Scala returns sbt compile`() {
        val cmd = SbtBuildTool.compileCommandFor(fakeSupport("scala", "Scala"), stubProject(tempDir.toString()))
        assertNotNull(cmd)
        assertEquals(listOf("sbt", "-batch", "-no-colors", "compile"), cmd!!.argv)
        assertEquals(File(tempDir.toString()), cmd.workingDir)
    }

    @Test fun `compileCommandFor Java returns sbt compile`() {
        // sbt's compile task compiles both Scala and Java source trees together.
        val cmd = SbtBuildTool.compileCommandFor(fakeSupport("java", "Java"), stubProject(tempDir.toString()))
        assertNotNull(cmd)
        assertEquals(listOf("sbt", "-batch", "-no-colors", "compile"), cmd!!.argv)
    }

    @Test fun `compileCommandFor Kotlin returns null`() {
        // sbt + Kotlin is uncommon and unsupported here.
        val cmd = SbtBuildTool.compileCommandFor(fakeSupport("kotlin", "Kotlin"), stubProject(tempDir.toString()))
        assertNull(cmd)
    }

    @Test fun `compileCommandFor unknown language returns null`() {
        val cmd = SbtBuildTool.compileCommandFor(fakeSupport("xyz", "XYZ"), stubProject(tempDir.toString()))
        assertNull(cmd)
    }

    @Test fun `compileCommandFor returns null when project has no basePath`() {
        val cmd = SbtBuildTool.compileCommandFor(fakeSupport("scala", "Scala"), stubProject(null))
        assertNull(cmd)
    }

    @Test fun `filterDiagnostics extracts error lines matching the target path`() {
        val output = """
            [info] welcome to sbt 1.12.11
            [info] loading project definition
            [error] /proj/src/main/scala/Foo.scala:42:5: not found: value bar
            [error] /proj/src/main/scala/Foo.scala:42:5:     bar
            [error]                                          ^
            [info] sbt.compiler.EvalException: ...
        """.trimIndent()
        val filtered = SbtBuildTool.filterDiagnostics(
            output, "/proj/src/main/scala/Foo.scala", "/proj",
        )
        assertTrue(
            "Expected error line in filtered output, got: <$filtered>",
            filtered.contains("[error]") && filtered.contains("not found: value bar"),
        )
    }

    @Test fun `filterDiagnostics extracts warning lines matching the target path`() {
        val output = """
            [info] compiling 3 Scala sources to /proj/target
            [warn] /proj/src/main/scala/Foo.scala:7:1: unused import
            [info] done
        """.trimIndent()
        val filtered = SbtBuildTool.filterDiagnostics(
            output, "/proj/src/main/scala/Foo.scala", "/proj",
        )
        assertTrue(filtered.contains("[warn]"))
        assertTrue(filtered.contains("unused import"))
    }

    @Test fun `filterDiagnostics returns blank when no line mentions the target`() {
        val output = """
            [info] welcome to sbt
            [error] /other/Bar.scala:1:1: something else
        """.trimIndent()
        val filtered = SbtBuildTool.filterDiagnostics(
            output, "/proj/src/main/scala/Foo.scala", "/proj",
        )
        assertTrue(
            "Expected blank filter, got: <$filtered>",
            filtered.isBlank(),
        )
    }
}
