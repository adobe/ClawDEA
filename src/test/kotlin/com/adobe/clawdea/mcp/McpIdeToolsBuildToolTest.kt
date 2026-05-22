/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.mcp

import com.adobe.clawdea.buildtool.BuildTool
import com.adobe.clawdea.buildtool.BuildToolRegistry
import com.adobe.clawdea.buildtool.CompileCommand
import com.adobe.clawdea.language.LanguageSupport
import com.adobe.clawdea.language.LanguageSupportRegistry
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class McpIdeToolsBuildToolTest {

    // Stub Language subclass — avoids IntelliJ's Language registry, which isn't
    // populated in this headless test's classpath.
    private object FakeJavaLang : Language("FAKE_JAVA_FOR_MCP_TEST") {
        override fun getDisplayName(): String = "Java (fake)"
    }

    private object FakeJavaSupport : LanguageSupport {
        override val language: Language = FakeJavaLang
        override val displayName = "Java"
        override val fileExtensions = setOf("java")
    }

    private class FakeBuildTool(
        override val id: String = "fake",
        override val displayName: String = "Fake",
        private val activeFor: (Project) -> Boolean = { true },
        private val command: CompileCommand? = null,
    ) : BuildTool {
        override fun isActive(project: Project) = activeFor(project)
        override fun buildConfigFiles(project: Project): List<VirtualFile> = emptyList()
        override fun compileCommandFor(language: Language, targetFile: String, project: Project): CompileCommand? = command
        override fun filterDiagnostics(output: String, targetFile: String, basePath: String): String = ""
    }

    private val stubProject: Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getBasePath" -> "/proj"
            "toString" -> "stubProject"
            "hashCode" -> 0
            "equals" -> false
            else -> null
        }
    } as Project

    @Before fun setUp() {
        BuildToolRegistry.clearForTest()
        LanguageSupportRegistry.clearForTest()
        LanguageSupportRegistry.register(FakeJavaSupport)
    }

    @After fun tearDown() {
        BuildToolRegistry.clearForTest()
        LanguageSupportRegistry.clearForTest()
    }

    @Test fun `resolveCompileCommand returns no-build-tool message when registry empty`() {
        val resolution = McpIdeTools.resolveCompileCommand("/proj/Foo.java", stubProject)
        assertNull(resolution.command)
        assertNull(resolution.buildTool)
        assertNotNull(resolution.result)
        assertEquals(McpIdeTools.NO_BUILD_TOOL_MSG, resolution.result!!.text)
        assertFalse(
            "no-build-tool result should be informational, not an error",
            resolution.result!!.isError,
        )
    }

    @Test fun `resolveCompileCommand returns unsupported-language when compileCommandFor returns null`() {
        val tool = FakeBuildTool(command = null)
        BuildToolRegistry.register(tool)

        val resolution = McpIdeTools.resolveCompileCommand("/proj/Foo.java", stubProject)
        assertSame(tool, resolution.buildTool)
        assertNull(resolution.command)
        assertNotNull(resolution.result)
        assertTrue(
            "Expected unsupported-language message, got: <${resolution.result!!.text}>",
            resolution.result!!.text.contains("does not support compiling"),
        )
        assertFalse(
            "unsupported-language result should be informational, not an error",
            resolution.result!!.isError,
        )
    }

    @Test fun `resolveCompileCommand returns unknown-extension error for unrecognised file`() {
        BuildToolRegistry.register(FakeBuildTool())
        val resolution = McpIdeTools.resolveCompileCommand("/proj/notes.xyz", stubProject)
        assertNull(resolution.command)
        assertNotNull(resolution.result)
        assertTrue(resolution.result!!.text.contains("No language support known"))
        assertTrue(
            "unknown-extension result should be flagged as error",
            resolution.result!!.isError,
        )
    }

    @Test fun `resolveCompileCommand returns command when build tool supports language`() {
        val cmd = CompileCommand(listOf("fake", "compile"), File("/proj"))
        BuildToolRegistry.register(FakeBuildTool(command = cmd))

        val resolution = McpIdeTools.resolveCompileCommand("/proj/Foo.java", stubProject)
        assertNull(resolution.result)
        assertNotNull(resolution.buildTool)
        assertSame(cmd, resolution.command)
    }
}
