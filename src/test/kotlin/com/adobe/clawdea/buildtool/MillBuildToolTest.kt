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

class MillBuildToolTest {

    private lateinit var tempDir: Path

    private fun fakeSupport(id: String, displayName: String = id) =
        fakeLanguageSupport(id, displayName)

    @Before fun setUp() {
        tempDir = Files.createTempDirectory("mill-build-tool-test")
    }

    @After fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test fun `id is mill`() {
        assertEquals("mill", MillBuildTool.id)
    }

    @Test fun `displayName is Mill`() {
        assertEquals("Mill", MillBuildTool.displayName)
    }

    @Test fun `compileCommandFor Scala uses mill wrapper when present`() {
        Files.createFile(tempDir.resolve("mill")).toFile().setExecutable(true)
        val cmd = MillBuildTool.compileCommandFor(fakeSupport("scala", "Scala"), stubProject(tempDir.toString()))
        assertNotNull(cmd)
        assertEquals(listOf("./mill", "--no-server", "__.compile"), cmd!!.argv)
        assertEquals(File(tempDir.toString()), cmd.workingDir)
    }

    @Test fun `compileCommandFor Java uses mill wrapper when present`() {
        Files.createFile(tempDir.resolve("mill")).toFile().setExecutable(true)
        val cmd = MillBuildTool.compileCommandFor(fakeSupport("java", "Java"), stubProject(tempDir.toString()))
        assertNotNull(cmd)
        assertEquals(listOf("./mill", "--no-server", "__.compile"), cmd!!.argv)
    }

    @Test fun `compileCommandFor falls back to mill on PATH when wrapper absent`() {
        val cmd = MillBuildTool.compileCommandFor(fakeSupport("scala", "Scala"), stubProject(tempDir.toString()))
        assertNotNull(cmd)
        assertEquals(listOf("mill", "--no-server", "__.compile"), cmd!!.argv)
    }

    @Test fun `compileCommandFor Kotlin returns null`() {
        val cmd = MillBuildTool.compileCommandFor(fakeSupport("kotlin", "Kotlin"), stubProject(tempDir.toString()))
        assertNull(cmd)
    }

    @Test fun `compileCommandFor unknown language returns null`() {
        val cmd = MillBuildTool.compileCommandFor(fakeSupport("xyz", "XYZ"), stubProject(tempDir.toString()))
        assertNull(cmd)
    }

    @Test fun `compileCommandFor returns null when project has no basePath`() {
        val cmd = MillBuildTool.compileCommandFor(fakeSupport("scala", "Scala"), stubProject(null))
        assertNull(cmd)
    }

    @Test fun `filterDiagnostics extracts error lines matching the target path`() {
        val output = """
            [info] compiling 2 Scala sources
            [error] /proj/src/Foo.scala:42:5: not found: value bar
            [error] /other/Bar.scala:1:1: unrelated
            [info] done
        """.trimIndent()
        val filtered = MillBuildTool.filterDiagnostics(
            output, "/proj/src/Foo.scala", "/proj",
        )
        assertTrue(
            "Expected error line in filtered output, got: <$filtered>",
            filtered.contains("[error]") && filtered.contains("not found: value bar"),
        )
        assertTrue(
            "Unrelated [error] line for /other/Bar.scala should be dropped, got: <$filtered>",
            !filtered.contains("Bar.scala"),
        )
    }

    @Test fun `filterDiagnostics extracts warning lines matching the target path`() {
        val output = """
            [warn] /proj/src/Foo.scala:7:1: deprecated
            [info] unrelated info
        """.trimIndent()
        val filtered = MillBuildTool.filterDiagnostics(
            output, "/proj/src/Foo.scala", "/proj",
        )
        assertTrue(filtered.contains("[warn]"))
        assertTrue(filtered.contains("deprecated"))
    }

    @Test fun `filterDiagnostics returns blank when no line mentions the target`() {
        val output = """
            [info] welcome to mill
            [error] /other/Bar.scala:1:1: something else
        """.trimIndent()
        val filtered = MillBuildTool.filterDiagnostics(
            output, "/proj/src/Foo.scala", "/proj",
        )
        assertTrue(
            "Expected blank filter, got: <$filtered>",
            filtered.isBlank(),
        )
    }

    // isActive (marker-file detection) is not unit-tested: it goes through
    // LocalFileSystem.getInstance() which requires ApplicationManager.getApplication()
    // to be non-null — not available in headless JUnit. This matches the deliberate
    // omission in SbtBuildToolTest and MavenBuildToolTest. Detection is exercised by
    // runIde smoke against a real Mill project.
}
