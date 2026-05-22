/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.buildtool

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class GradleBuildToolTest {

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

    @Test fun `id is gradle`() {
        assertEquals("gradle", GradleBuildTool.id)
    }

    @Test fun `displayName is Gradle`() {
        assertEquals("Gradle", GradleBuildTool.displayName)
    }

    @Test fun `compileCommandFor Java returns gradlew compileJava quiet`() {
        val java = Language.findLanguageByID("JAVA")
        assumeNotNull(java)
        val cmd = GradleBuildTool.compileCommandFor(java!!, "/proj/Foo.java", stubProject("/proj"))
        assertNotNull(cmd)
        assertEquals(listOf("./gradlew", "compileJava", "--quiet"), cmd!!.argv)
        assertEquals(File("/proj"), cmd.workingDir)
    }

    @Test fun `compileCommandFor Kotlin returns gradlew compileKotlin quiet`() {
        val kotlin = Language.findLanguageByID("kotlin")
        assumeNotNull(kotlin)
        val cmd = GradleBuildTool.compileCommandFor(kotlin!!, "/proj/Foo.kt", stubProject("/proj"))
        assertNotNull(cmd)
        assertEquals(listOf("./gradlew", "compileKotlin", "--quiet"), cmd!!.argv)
    }

    @Test fun `compileCommandFor unknown language returns null`() {
        // Use a language we know isn't supported. Plain text always exists.
        val text = Language.findLanguageByID("TEXT")
        assumeNotNull(text)
        val cmd = GradleBuildTool.compileCommandFor(text!!, "/proj/notes.txt", stubProject("/proj"))
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
