/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.buildtool

import com.adobe.clawdea.language.LanguageSupport
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

class SbtBuildToolTest {

    private lateinit var tempDir: Path

    private fun fakeSupport(supportId: String, supportDisplayName: String = supportId): LanguageSupport =
        object : LanguageSupport {
            override val id = supportId
            override val language: Language? = null
            override val displayName = supportDisplayName
            override val fileExtensions = emptySet<String>()
        }

    @Before fun setUp() {
        tempDir = Files.createTempDirectory("sbt-build-tool-test")
    }

    @After fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun stubProject(basePath: String?): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getBasePath" -> basePath
            "toString" -> "stubProject($basePath)"
            "hashCode" -> System.identityHashCode(basePath)
            "equals" -> false
            else -> null
        }
    } as Project

    @Test fun `id is sbt`() {
        assertEquals("sbt", SbtBuildTool.id)
    }

    @Test fun `displayName is sbt`() {
        assertEquals("sbt", SbtBuildTool.displayName)
    }

    @Test fun `compileCommandFor Scala returns sbt compile`() {
        val cmd = SbtBuildTool.compileCommandFor(
            fakeSupport("scala", "Scala"),
            "${tempDir}/src/main/scala/Foo.scala",
            stubProject(tempDir.toString()),
        )
        assertNotNull(cmd)
        assertEquals(listOf("sbt", "-batch", "-no-colors", "compile"), cmd!!.argv)
        assertEquals(File(tempDir.toString()), cmd.workingDir)
    }

    @Test fun `compileCommandFor Java returns sbt compile`() {
        // sbt's compile task compiles both Scala and Java source trees together.
        val cmd = SbtBuildTool.compileCommandFor(
            fakeSupport("java", "Java"),
            "${tempDir}/src/main/java/Foo.java",
            stubProject(tempDir.toString()),
        )
        assertNotNull(cmd)
        assertEquals(listOf("sbt", "-batch", "-no-colors", "compile"), cmd!!.argv)
    }

    @Test fun `compileCommandFor Kotlin returns null`() {
        // sbt + Kotlin is uncommon and unsupported here.
        val cmd = SbtBuildTool.compileCommandFor(
            fakeSupport("kotlin", "Kotlin"),
            "${tempDir}/src/main/kotlin/Foo.kt",
            stubProject(tempDir.toString()),
        )
        assertNull(cmd)
    }

    @Test fun `compileCommandFor unknown language returns null`() {
        val cmd = SbtBuildTool.compileCommandFor(
            fakeSupport("xyz", "XYZ"),
            "${tempDir}/notes.xyz",
            stubProject(tempDir.toString()),
        )
        assertNull(cmd)
    }

    @Test fun `compileCommandFor returns null when project has no basePath`() {
        val cmd = SbtBuildTool.compileCommandFor(
            fakeSupport("scala", "Scala"),
            "${tempDir}/src/main/scala/Foo.scala",
            stubProject(null),
        )
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
