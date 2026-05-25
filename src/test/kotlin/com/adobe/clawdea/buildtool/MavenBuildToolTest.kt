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

class MavenBuildToolTest {

    private lateinit var tempDir: Path

    private fun fakeSupport(id: String, displayName: String = id) =
        fakeLanguageSupport(id, displayName)

    @Before fun setUp() {
        tempDir = Files.createTempDirectory("maven-build-tool-test")
    }

    @After fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test fun `id is maven`() {
        assertEquals("maven", MavenBuildTool.id)
    }

    @Test fun `displayName is Maven`() {
        assertEquals("Maven", MavenBuildTool.displayName)
    }

    @Test fun `compileCommandFor Java uses mvnw when wrapper exists`() {
        Files.createFile(tempDir.resolve("mvnw")).toFile().setExecutable(true)
        val cmd = MavenBuildTool.compileCommandFor(fakeSupport("java", "Java"), stubProject(tempDir.toString()))
        assertNotNull(cmd)
        assertEquals(listOf("./mvnw", "compile", "-q"), cmd!!.argv)
        assertEquals(File(tempDir.toString()), cmd.workingDir)
    }

    @Test fun `compileCommandFor Java falls back to mvn when wrapper absent`() {
        val cmd = MavenBuildTool.compileCommandFor(fakeSupport("java", "Java"), stubProject(tempDir.toString()))
        assertNotNull(cmd)
        assertEquals(listOf("mvn", "compile", "-q"), cmd!!.argv)
    }

    @Test fun `compileCommandFor Kotlin returns null when kotlin-maven-plugin is absent from pom`() {
        // No pom.xml in tempDir -> PomReader.hasPlugin returns false -> allowed = {java}.
        val cmd = MavenBuildTool.compileCommandFor(fakeSupport("kotlin", "Kotlin"), stubProject(tempDir.toString()))
        assertNull(cmd)
    }

    @Test fun `compileCommandFor Scala returns null when scala-maven-plugin is absent from pom`() {
        val cmd = MavenBuildTool.compileCommandFor(fakeSupport("scala", "Scala"), stubProject(tempDir.toString()))
        assertNull(cmd)
    }

    @Test fun `compileCommandFor Kotlin returns mvn command when kotlin-maven-plugin is in pom`() {
        writePomWithPlugin("org.jetbrains.kotlin", "kotlin-maven-plugin")
        val cmd = MavenBuildTool.compileCommandFor(fakeSupport("kotlin", "Kotlin"), stubProject(tempDir.toString()))
        assertNotNull(cmd)
        assertEquals(listOf("mvn", "compile", "-q"), cmd!!.argv)
    }

    @Test fun `compileCommandFor Scala returns mvn command when scala-maven-plugin is in pom`() {
        writePomWithPlugin("net.alchim31.maven", "scala-maven-plugin")
        val cmd = MavenBuildTool.compileCommandFor(fakeSupport("scala", "Scala"), stubProject(tempDir.toString()))
        assertNotNull(cmd)
        assertEquals(listOf("mvn", "compile", "-q"), cmd!!.argv)
    }

    @Test fun `compileCommandFor unknown language returns null`() {
        val cmd = MavenBuildTool.compileCommandFor(fakeSupport("xyz", "XYZ"), stubProject(tempDir.toString()))
        assertNull(cmd)
    }

    private fun writePomWithPlugin(groupId: String, artifactId: String) {
        val pom = tempDir.resolve("pom.xml").toFile()
        pom.writeText("""<?xml version="1.0" encoding="UTF-8"?>
            <project>
              <groupId>com.example</groupId>
              <artifactId>test</artifactId>
              <version>1.0.0</version>
              <build>
                <plugins>
                  <plugin>
                    <groupId>$groupId</groupId>
                    <artifactId>$artifactId</artifactId>
                  </plugin>
                </plugins>
              </build>
            </project>
        """.trimIndent())
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
