/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.buildtool

import com.adobe.clawdea.language.LanguageSupport
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class GradleBuildToolTest {

    private fun fakeSupport(supportId: String, supportDisplayName: String = supportId): LanguageSupport =
        object : LanguageSupport {
            override val id = supportId
            override val language: Language? = null
            override val displayName = supportDisplayName
            override val fileExtensions = emptySet<String>()
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

    @Test fun `id is gradle`() {
        assertEquals("gradle", GradleBuildTool.id)
    }

    @Test fun `displayName is Gradle`() {
        assertEquals("Gradle", GradleBuildTool.displayName)
    }

    @Test fun `compileCommandFor Java returns gradlew compileJava quiet`() {
        val cmd = GradleBuildTool.compileCommandFor(
            fakeSupport("java", "Java"), "/proj/Foo.java", stubProject("/proj"),
        )
        assertNotNull(cmd)
        assertEquals(listOf("./gradlew", "compileJava", "--quiet"), cmd!!.argv)
        assertEquals(File("/proj"), cmd.workingDir)
    }

    @Test fun `compileCommandFor Kotlin returns gradlew compileKotlin quiet`() {
        val cmd = GradleBuildTool.compileCommandFor(
            fakeSupport("kotlin", "Kotlin"), "/proj/Foo.kt", stubProject("/proj"),
        )
        assertNotNull(cmd)
        assertEquals(listOf("./gradlew", "compileKotlin", "--quiet"), cmd!!.argv)
    }

    @Test fun `compileCommandFor unknown language returns null`() {
        val cmd = GradleBuildTool.compileCommandFor(
            fakeSupport("xyz", "XYZ"), "/proj/notes.xyz", stubProject("/proj"),
        )
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
