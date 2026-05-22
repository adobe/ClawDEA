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

class MavenBuildToolTest {

    private lateinit var tempDir: Path

    private fun fakeSupport(supportId: String, supportDisplayName: String = supportId): LanguageSupport =
        object : LanguageSupport {
            override val id = supportId
            override val language: Language? = null
            override val displayName = supportDisplayName
            override val fileExtensions = emptySet<String>()
        }

    @Before fun setUp() {
        tempDir = Files.createTempDirectory("maven-build-tool-test")
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

    @Test fun `id is maven`() {
        assertEquals("maven", MavenBuildTool.id)
    }

    @Test fun `displayName is Maven`() {
        assertEquals("Maven", MavenBuildTool.displayName)
    }

    @Test fun `compileCommandFor Java uses mvnw when wrapper exists`() {
        Files.createFile(tempDir.resolve("mvnw")).toFile().setExecutable(true)
        val cmd = MavenBuildTool.compileCommandFor(
            fakeSupport("java", "Java"),
            "${tempDir}/src/main/java/Foo.java",
            stubProject(tempDir.toString()),
        )
        assertNotNull(cmd)
        assertEquals(listOf("./mvnw", "compile", "-q"), cmd!!.argv)
        assertEquals(File(tempDir.toString()), cmd.workingDir)
    }

    @Test fun `compileCommandFor Java falls back to mvn when wrapper absent`() {
        val cmd = MavenBuildTool.compileCommandFor(
            fakeSupport("java", "Java"),
            "${tempDir}/src/main/java/Foo.java",
            stubProject(tempDir.toString()),
        )
        assertNotNull(cmd)
        assertEquals(listOf("mvn", "compile", "-q"), cmd!!.argv)
    }

    @Test fun `compileCommandFor Kotlin returns null in this PR`() {
        // Maven + Kotlin is real (kotlin-maven-plugin) but #2 does not detect it.
        val cmd = MavenBuildTool.compileCommandFor(
            fakeSupport("kotlin", "Kotlin"),
            "${tempDir}/src/main/kotlin/Foo.kt",
            stubProject(tempDir.toString()),
        )
        assertNull(cmd)
    }

    @Test fun `compileCommandFor unknown language returns null`() {
        val cmd = MavenBuildTool.compileCommandFor(
            fakeSupport("xyz", "XYZ"),
            "${tempDir}/notes.xyz",
            stubProject(tempDir.toString()),
        )
        assertNull(cmd)
    }

    @Test fun `filterDiagnostics extracts ERROR lines matching the target path`() {
        val output = """
            [INFO] Scanning for projects...
            [INFO] Building module 1.0
            [ERROR] /proj/src/main/java/Foo.java:[42,15] cannot find symbol
            [INFO] BUILD FAILURE
        """.trimIndent()
        val filtered = MavenBuildTool.filterDiagnostics(
            output, "/proj/src/main/java/Foo.java", "/proj",
        )
        assertTrue(
            "Expected ERROR line in filtered output, got: <$filtered>",
            filtered.contains("[ERROR]") && filtered.contains("cannot find symbol"),
        )
    }

    @Test fun `filterDiagnostics extracts WARNING lines matching the target path`() {
        val output = """
            [INFO] some plugin output
            [WARNING] /proj/src/main/java/Foo.java:[100,5] deprecated API
            [INFO] more output
        """.trimIndent()
        val filtered = MavenBuildTool.filterDiagnostics(
            output, "/proj/src/main/java/Foo.java", "/proj",
        )
        assertTrue(filtered.contains("[WARNING]"))
        assertTrue(filtered.contains("deprecated API"))
    }

    @Test fun `filterDiagnostics returns blank when no match`() {
        val output = """
            [INFO] Scanning for projects...
            [ERROR] /other/Bar.java:[1,1] something else
        """.trimIndent()
        val filtered = MavenBuildTool.filterDiagnostics(
            output, "/proj/src/main/java/Foo.java", "/proj",
        )
        assertTrue(
            "Expected blank filter, got: <$filtered>",
            filtered.isBlank(),
        )
    }
}
