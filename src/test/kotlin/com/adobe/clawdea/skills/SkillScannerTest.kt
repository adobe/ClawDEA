/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.skills

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class SkillScannerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun createSkillFile(vendor: String, plugin: String, version: String, skillName: String, content: String): File {
        val dir = File(tempDir.root, "$vendor/$plugin/$version/skills/$skillName")
        dir.mkdirs()
        val file = File(dir, "SKILL.md")
        file.writeText(content)
        return file
    }

    private fun createFlatSkillFile(root: File, skillName: String, content: String): File {
        val dir = File(root, skillName)
        dir.mkdirs()
        val file = File(dir, "SKILL.md")
        file.writeText(content)
        return file
    }

    @Test
    fun `scans skills from plugins cache directory`() {
        createSkillFile("claude-plugins-official", "superpowers", "5.0.7", "brainstorming", """
            ---
            name: brainstorming
            description: Brainstorm ideas into designs
            ---
            # Content
        """.trimIndent())

        createSkillFile("claude-plugins-official", "superpowers", "5.0.7", "systematic-debugging", """
            ---
            name: systematic-debugging
            description: Debug systematically
            ---
        """.trimIndent())

        val scanner = SkillScanner(tempDir.root.toPath())
        val skills = scanner.scan()

        assertEquals(2, skills.size)
        val brainstorm = skills.find { it.name == "brainstorming" }
        assertNotNull(brainstorm)
        assertEquals("superpowers:brainstorming", brainstorm!!.qualifiedName)
        assertEquals("superpowers", brainstorm.pluginName)
        assertEquals("5.0.7", brainstorm.pluginVersion)
        assertEquals("Brainstorm ideas into designs", brainstorm.description)
    }

    @Test
    fun `generates short aliases when no collision`() {
        createSkillFile("claude-plugins-official", "superpowers", "5.0.7", "brainstorming", """
            ---
            name: brainstorming
            description: Brainstorm
            ---
        """.trimIndent())

        val scanner = SkillScanner(tempDir.root.toPath())
        val skills = scanner.scan()
        val skill = skills.first()
        assertTrue(skill.aliases.contains("/brainstorming"))
        assertTrue(skill.aliases.contains("/superpowers:brainstorming"))
    }

    @Test
    fun `suppresses short alias on name collision across plugins`() {
        createSkillFile("claude-plugins-official", "superpowers", "1.0", "commit", """
            ---
            name: commit
            description: Commit from superpowers
            ---
        """.trimIndent())

        createSkillFile("claude-plugins-official", "commit-commands", "1.0", "commit", """
            ---
            name: commit
            description: Commit from commit-commands
            ---
        """.trimIndent())

        val scanner = SkillScanner(tempDir.root.toPath())
        val skills = scanner.scan()

        assertEquals(2, skills.size)
        for (skill in skills) {
            assertFalse(
                "Skill ${skill.qualifiedName} should not have short alias /commit",
                skill.aliases.contains("/commit"),
            )
            assertTrue(skill.aliases.contains("/${skill.qualifiedName}"))
        }
    }

    @Test
    fun `skips malformed skill files`() {
        createSkillFile("claude-plugins-official", "superpowers", "1.0", "good", """
            ---
            name: good
            description: A good skill
            ---
        """.trimIndent())

        createSkillFile("claude-plugins-official", "superpowers", "1.0", "bad", """
            ---
            description: Missing name
            ---
        """.trimIndent())

        val scanner = SkillScanner(tempDir.root.toPath())
        val skills = scanner.scan()

        assertEquals(1, skills.size)
        assertEquals("good", skills[0].name)
    }

    @Test
    fun `returns empty list when cache directory does not exist`() {
        val scanner = SkillScanner(tempDir.root.toPath().resolve("nonexistent"))
        val skills = scanner.scan()
        assertTrue(skills.isEmpty())
    }

    @Test
    fun `scans skills from multiple plugins`() {
        createSkillFile("claude-plugins-official", "superpowers", "5.0.7", "brainstorming", """
            ---
            name: brainstorming
            description: Brainstorm
            ---
        """.trimIndent())

        createSkillFile("claude-plugins-official", "atlassian", "1.0", "triage-issue", """
            ---
            name: triage-issue
            description: Triage bugs
            ---
        """.trimIndent())

        val scanner = SkillScanner(tempDir.root.toPath())
        val skills = scanner.scan()

        assertEquals(2, skills.size)
        assertNotNull(skills.find { it.pluginName == "superpowers" })
        assertNotNull(skills.find { it.pluginName == "atlassian" })
    }

    @Test
    fun `plugin cache layout tolerates missing version directory`() {
        // Some plugins store skills directly under <plugin>/skills/ without a version level
        val dir = File(tempDir.root, "claude-plugins-official/legacy-plugin/skills/foo")
        dir.mkdirs()
        File(dir, "SKILL.md").writeText("""
            ---
            name: foo
            description: A legacy-layout skill
            ---
        """.trimIndent())

        val scanner = SkillScanner(tempDir.root.toPath())
        val skills = scanner.scan()

        val foo = skills.find { it.name == "foo" }
        assertNotNull("should find skill in <plugin>/skills/ layout", foo)
        assertEquals("legacy-plugin", foo!!.pluginName)
        assertEquals("unknown", foo.pluginVersion)
    }

    @Test
    fun `deduplicates same plugin-skill across version directories, newest mtime wins`() {
        val older = createSkillFile("claude-plugins-official", "atlassian", "385c1469c567", "triage-issue", """
            ---
            name: triage-issue
            description: Old version
            ---
        """.trimIndent())
        val newer = createSkillFile("claude-plugins-official", "atlassian", "9b52fb18e184", "triage-issue", """
            ---
            name: triage-issue
            description: New version
            ---
        """.trimIndent())

        Files.setLastModifiedTime(older.toPath(), FileTime.fromMillis(1_000_000))
        Files.setLastModifiedTime(newer.toPath(), FileTime.fromMillis(2_000_000))

        val scanner = SkillScanner(tempDir.root.toPath())
        val skills = scanner.scan()

        val triage = skills.filter { it.qualifiedName == "atlassian:triage-issue" }
        assertEquals("should collapse multi-version duplicates to one entry", 1, triage.size)
        assertEquals("9b52fb18e184", triage[0].pluginVersion)
        assertEquals("New version", triage[0].description)
    }

    @Test
    fun `scans flat user-scoped skills layout`() {
        val userRoot = tempDir.newFolder("user-skills")
        createFlatSkillFile(userRoot, "my-skill", """
            ---
            name: my-skill
            description: A user-installed skill
            ---
        """.trimIndent())

        val scanner = SkillScanner(
            listOf(SkillRoot.Flat(userRoot.toPath(), pluginName = "user")),
        )
        val skills = scanner.scan()

        assertEquals(1, skills.size)
        val skill = skills[0]
        assertEquals("my-skill", skill.name)
        assertEquals("user", skill.pluginName)
        assertEquals("user:my-skill", skill.qualifiedName)
    }

    @Test
    fun `merges skills from multiple roots`() {
        // plugin-cache root
        createSkillFile("claude-plugins-official", "superpowers", "5.0.7", "brainstorming", """
            ---
            name: brainstorming
            description: From plugin
            ---
        """.trimIndent())
        // user-scoped flat root
        val userRoot = tempDir.newFolder("user-skills")
        createFlatSkillFile(userRoot, "my-skill", """
            ---
            name: my-skill
            description: From user
            ---
        """.trimIndent())

        val scanner = SkillScanner(listOf(
            SkillRoot.PluginCache(tempDir.root.toPath()),
            SkillRoot.Flat(userRoot.toPath(), pluginName = "user"),
        ))
        val skills = scanner.scan()

        assertEquals(2, skills.size)
        assertNotNull(skills.find { it.pluginName == "superpowers" })
        assertNotNull(skills.find { it.pluginName == "user" })
    }

    @Test
    fun `flat duplicate of plugin-cache skill is dropped`() {
        // Plugin cache has resolve-jira-ticket from acme-agent-toolkit
        createSkillFile("claude-plugins-official", "acme-agent-toolkit", "1.0", "resolve-jira-ticket", """
            ---
            name: resolve-jira-ticket
            description: Resolve a Jira ticket
            ---
        """.trimIndent())
        // User flat root also has resolve-jira-ticket
        val userRoot = tempDir.newFolder("user-skills")
        createFlatSkillFile(userRoot, "resolve-jira-ticket", """
            ---
            name: resolve-jira-ticket
            description: Resolve a Jira ticket (user copy)
            ---
        """.trimIndent())

        val scanner = SkillScanner(listOf(
            SkillRoot.PluginCache(tempDir.root.toPath()),
            SkillRoot.Flat(userRoot.toPath(), pluginName = "user"),
        ))
        val skills = scanner.scan()

        val matches = skills.filter { it.name == "resolve-jira-ticket" }
        assertEquals("flat duplicate should be dropped", 1, matches.size)
        assertEquals("acme-agent-toolkit", matches[0].pluginName)
        assertTrue(
            "should have short alias since no collision remains",
            matches[0].aliases.contains("/resolve-jira-ticket"),
        )
    }

    @Test
    fun `scanWithStats reports counts for roots scanned, missing, rejected`() {
        createSkillFile("claude-plugins-official", "superpowers", "1.0", "good", """
            ---
            name: good
            description: Good
            ---
        """.trimIndent())
        createSkillFile("claude-plugins-official", "superpowers", "1.0", "bad", """
            ---
            description: Missing name
            ---
        """.trimIndent())

        val missingRoot = tempDir.root.toPath().resolve("nope")
        val scanner = SkillScanner(listOf(
            SkillRoot.PluginCache(tempDir.root.toPath()),
            SkillRoot.PluginCache(missingRoot),
        ))
        val result = scanner.scanWithStats()

        assertEquals(1, result.skills.size)
        assertEquals(1, result.rootsScanned)
        assertEquals(1, result.rootsMissing)
        assertEquals(1, result.rejectedCount)
    }
}
