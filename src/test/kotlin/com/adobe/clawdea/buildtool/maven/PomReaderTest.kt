/*
 * Copyright 2026 Adobe. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.adobe.clawdea.buildtool.maven

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class PomReaderTest {

    private lateinit var tempDir: Path

    @Before fun setUp() {
        tempDir = Files.createTempDirectory("pom-reader-test")
    }

    @After fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun writePom(relativePath: String, content: String): File {
        val file = tempDir.resolve(relativePath).toFile()
        file.parentFile.mkdirs()
        file.writeText(content)
        return file
    }

    private fun parentPomXml(modules: List<String>, plugins: List<String> = emptyList(), managed: List<String> = emptyList()): String {
        val modulesBlock = if (modules.isEmpty()) "" else
            "<modules>${modules.joinToString("") { "<module>$it</module>" }}</modules>"
        val pluginsBlock = if (plugins.isEmpty()) "" else
            "<plugins>${plugins.joinToString("")}</plugins>"
        val managedBlock = if (managed.isEmpty()) "" else
            "<pluginManagement><plugins>${managed.joinToString("")}</plugins></pluginManagement>"
        val buildBlock = if (pluginsBlock.isEmpty() && managedBlock.isEmpty()) "" else
            "<build>$pluginsBlock$managedBlock</build>"
        return """<?xml version="1.0" encoding="UTF-8"?>
            <project>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              $modulesBlock
              $buildBlock
            </project>
        """.trimIndent()
    }

    private fun childPomXml(name: String): String = """<?xml version="1.0" encoding="UTF-8"?>
        <project>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
          </parent>
          <artifactId>$name</artifactId>
        </project>
    """.trimIndent()

    @Test fun `readModules returns module names from parent pom`() {
        val pom = writePom("pom.xml", parentPomXml(listOf("core", "api")))
        assertEquals(listOf("core", "api"), PomReader.readModules(pom))
    }

    @Test fun `readModules returns empty list when no modules declared`() {
        val pom = writePom("pom.xml", parentPomXml(emptyList()))
        assertEquals(emptyList<String>(), PomReader.readModules(pom))
    }

    @Test fun `readModules returns empty list for non-existent file`() {
        val pom = tempDir.resolve("missing.xml").toFile()
        assertEquals(emptyList<String>(), PomReader.readModules(pom))
    }

    @Test fun `hasPlugin detects plugin in build plugins`() {
        val plugin = "<plugin><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId></plugin>"
        val pom = writePom("pom.xml", parentPomXml(emptyList(), plugins = listOf(plugin)))
        assertTrue(PomReader.hasPlugin(pom, "org.jetbrains.kotlin", "kotlin-maven-plugin"))
    }

    @Test fun `hasPlugin detects plugin in pluginManagement`() {
        val plugin = "<plugin><groupId>net.alchim31.maven</groupId><artifactId>scala-maven-plugin</artifactId></plugin>"
        val pom = writePom("pom.xml", parentPomXml(emptyList(), managed = listOf(plugin)))
        assertTrue(PomReader.hasPlugin(pom, "net.alchim31.maven", "scala-maven-plugin"))
    }

    @Test fun `hasPlugin matches artifactId-only when groupId is absent (Maven default-groupId)`() {
        val plugin = "<plugin><artifactId>some-plugin</artifactId></plugin>"
        val pom = writePom("pom.xml", parentPomXml(emptyList(), plugins = listOf(plugin)))
        assertTrue(PomReader.hasPlugin(pom, "org.apache.maven.plugins", "some-plugin"))
    }

    @Test fun `hasPlugin returns false when artifactId does not match`() {
        val plugin = "<plugin><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId></plugin>"
        val pom = writePom("pom.xml", parentPomXml(emptyList(), plugins = listOf(plugin)))
        assertFalse(PomReader.hasPlugin(pom, "org.jetbrains.kotlin", "different-plugin"))
    }

    @Test fun `hasPlugin returns false when no plugins declared`() {
        val pom = writePom("pom.xml", parentPomXml(emptyList()))
        assertFalse(PomReader.hasPlugin(pom, "anything", "anything"))
    }

    @Test fun `walkPomTree returns parent plus all children`() {
        val parentPom = writePom("pom.xml", parentPomXml(listOf("core", "api")))
        writePom("core/pom.xml", childPomXml("core"))
        writePom("api/pom.xml", childPomXml("api"))
        val tree = PomReader.walkPomTree(parentPom)
        assertEquals(3, tree.size)
        assertTrue("parent should appear", tree.any { it.canonicalPath == parentPom.canonicalPath })
        assertTrue("core child should appear", tree.any { it.name == "pom.xml" && it.parentFile.name == "core" })
        assertTrue("api child should appear", tree.any { it.name == "pom.xml" && it.parentFile.name == "api" })
    }

    @Test fun `walkPomTree silently skips missing module pom`() {
        // Parent declares a 'core' module but the file doesn't exist.
        val parentPom = writePom("pom.xml", parentPomXml(listOf("core")))
        val tree = PomReader.walkPomTree(parentPom)
        assertEquals(1, tree.size)
        assertEquals(parentPom.canonicalPath, tree[0].canonicalPath)
    }

    @Test fun `walkPomTree guards against self-cycle via dotdot path`() {
        // Parent declares a 'self' module that resolves back to the same directory.
        val parentPom = writePom("pom.xml", parentPomXml(listOf(".")))
        val tree = PomReader.walkPomTree(parentPom)
        assertEquals("cycle should keep only the root", 1, tree.size)
    }

    @Test fun `walkPomTree respects maxDepth cap`() {
        // a -> a/b -> a/b/c -> ... at depth 0 we visit the root, depth 1 the first child, etc.
        val parentPom = writePom("pom.xml", parentPomXml(listOf("a")))
        writePom("a/pom.xml", parentPomXml(listOf("b")))
        writePom("a/b/pom.xml", parentPomXml(listOf("c")))
        writePom("a/b/c/pom.xml", childPomXml("c"))
        val tree = PomReader.walkPomTree(parentPom, maxDepth = 1)
        // depth 0 (root) + depth 1 ('a') = 2 entries.
        assertEquals(2, tree.size)
    }
}
