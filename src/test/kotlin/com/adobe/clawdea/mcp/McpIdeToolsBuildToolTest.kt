/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.mcp

import com.adobe.clawdea.buildtool.BuildTool
import com.adobe.clawdea.buildtool.BuildToolRegistry
import com.adobe.clawdea.buildtool.CompileCommand
import com.adobe.clawdea.buildtool.fakeLanguageSupport
import com.adobe.clawdea.buildtool.stubProject
import com.adobe.clawdea.language.LanguageSupport
import com.adobe.clawdea.language.LanguageSupportRegistry
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class McpIdeToolsBuildToolTest {

    // Stub Language subclass — IntelliJ's Language registry isn't populated in
    // headless tests, so we register a synthetic Language under our LanguageSupport.
    private object FakeJavaLang : Language("FAKE_JAVA_FOR_MCP_TEST") {
        override fun getDisplayName(): String = "Java (fake)"
    }

    private val javaSupport: LanguageSupport = fakeLanguageSupport(
        id = "java",
        displayName = "Java",
        fileExtensions = setOf("java"),
        language = FakeJavaLang,
    )

    private class FakeBuildTool(
        override val id: String = "fake",
        override val displayName: String = "Fake",
        private val activeFor: (Project) -> Boolean = { true },
        private val command: CompileCommand? = null,
    ) : BuildTool {
        override fun isActive(project: Project) = activeFor(project)
        override fun buildConfigFiles(project: Project): List<VirtualFile> = emptyList()
        override fun compileCommandFor(languageSupport: LanguageSupport, project: Project): CompileCommand? = command
        override fun filterDiagnostics(output: String, targetFile: String, basePath: String): String = ""
    }

    private val project: Project = stubProject(basePath = "/proj")

    @Before fun setUp() {
        BuildToolRegistry.clearForTest()
        LanguageSupportRegistry.clearForTest()
        LanguageSupportRegistry.register(javaSupport)
    }

    @After fun tearDown() {
        BuildToolRegistry.clearForTest()
        LanguageSupportRegistry.clearForTest()
    }

    private fun earlyResult(resolution: McpIdeTools.Companion.CompileResolution) =
        (resolution as McpIdeTools.Companion.CompileResolution.EarlyResult).result

    @Test fun `resolveCompileCommand returns no-build-tool message when registry empty`() {
        val result = earlyResult(McpIdeTools.resolveCompileCommand("/proj/Foo.java", project))
        assertEquals(McpIdeTools.NO_BUILD_TOOL_MSG, result.text)
        assertFalse(
            "no-build-tool result should be informational, not an error",
            result.isError,
        )
    }

    @Test fun `resolveCompileCommand returns unsupported-language when compileCommandFor returns null`() {
        BuildToolRegistry.register(FakeBuildTool(command = null))

        val result = earlyResult(McpIdeTools.resolveCompileCommand("/proj/Foo.java", project))
        assertTrue(
            "Expected unsupported-language message, got: <${result.text}>",
            result.text.contains("does not support compiling"),
        )
        assertFalse(
            "unsupported-language result should be informational, not an error",
            result.isError,
        )
    }

    @Test fun `resolveCompileCommand returns unknown-extension error for unrecognised file`() {
        BuildToolRegistry.register(FakeBuildTool())
        val result = earlyResult(McpIdeTools.resolveCompileCommand("/proj/notes.xyz", project))
        assertTrue(result.text.contains("No language support known"))
        assertTrue(
            "unknown-extension result should be flagged as error",
            result.isError,
        )
    }

    @Test fun `resolveCompileCommand returns command when build tool supports language`() {
        val cmd = CompileCommand(listOf("fake", "compile"), File("/proj"))
        BuildToolRegistry.register(FakeBuildTool(command = cmd))

        val resolution = McpIdeTools.resolveCompileCommand("/proj/Foo.java", project)
        assertTrue(resolution is McpIdeTools.Companion.CompileResolution.Ready)
        assertSame(cmd, (resolution as McpIdeTools.Companion.CompileResolution.Ready).command)
    }
}
