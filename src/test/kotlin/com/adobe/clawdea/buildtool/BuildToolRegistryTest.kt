/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.buildtool

import com.adobe.clawdea.language.LanguageSupport
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BuildToolRegistryTest {

    private class FakeBuildTool(
        override val id: String,
        override val displayName: String,
        private val activeFor: (Project) -> Boolean = { false },
    ) : BuildTool {
        override fun isActive(project: Project) = activeFor(project)
        override fun buildConfigFiles(project: Project): List<VirtualFile> = emptyList()
        override fun compileCommandFor(languageSupport: LanguageSupport, project: Project): CompileCommand? = null
        override fun filterDiagnostics(output: String, targetFile: String, basePath: String): String = ""
    }

    private val project: Project = stubProject(basePath = "/proj")

    @Before fun setUp() { BuildToolRegistry.clearForTest() }
    @After  fun tearDown() { BuildToolRegistry.clearForTest() }

    @Test fun `register then all contains the tool`() {
        val t = FakeBuildTool("gradle", "Gradle")
        BuildToolRegistry.register(t)
        assertTrue(BuildToolRegistry.all().contains(t))
    }

    @Test fun `re-register same id replaces prior entry`() {
        val first = FakeBuildTool("gradle", "Gradle v1")
        val second = FakeBuildTool("gradle", "Gradle v2")
        BuildToolRegistry.register(first)
        BuildToolRegistry.register(second)
        assertEquals(1, BuildToolRegistry.all().size)
        assertSame(second, BuildToolRegistry.all().first())
    }

    @Test fun `detectPrimary returns first active by registration order`() {
        val gradle = FakeBuildTool("gradle", "Gradle", activeFor = { true })
        val maven = FakeBuildTool("maven", "Maven", activeFor = { true })
        BuildToolRegistry.register(gradle)
        BuildToolRegistry.register(maven)
        assertSame(gradle, BuildToolRegistry.detectPrimary(project))
    }

    @Test fun `detectPrimary returns null when none active`() {
        BuildToolRegistry.register(FakeBuildTool("gradle", "Gradle"))
        assertNull(BuildToolRegistry.detectPrimary(project))
    }

    @Test fun `detectAll returns all active in registration order`() {
        val gradle = FakeBuildTool("gradle", "Gradle", activeFor = { true })
        val maven = FakeBuildTool("maven", "Maven", activeFor = { true })
        val sbt = FakeBuildTool("sbt", "sbt", activeFor = { false })
        BuildToolRegistry.register(gradle)
        BuildToolRegistry.register(maven)
        BuildToolRegistry.register(sbt)
        val active = BuildToolRegistry.detectAll(project)
        assertEquals(listOf(gradle, maven), active)
    }

    @Test fun `all returns snapshot that does not mutate the registry`() {
        BuildToolRegistry.register(FakeBuildTool("gradle", "Gradle"))
        BuildToolRegistry.register(FakeBuildTool("maven", "Maven"))
        val snapshot = BuildToolRegistry.all().toMutableList()
        snapshot.clear()
        assertEquals(2, BuildToolRegistry.all().size)
    }
}
