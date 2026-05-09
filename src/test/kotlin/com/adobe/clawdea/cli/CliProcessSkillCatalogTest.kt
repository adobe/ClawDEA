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
package com.adobe.clawdea.cli

import com.adobe.clawdea.skills.SkillInfo
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths

class CliProcessSkillCatalogTest {

    private fun skill(name: String, qualifiedName: String, description: String) = SkillInfo(
        name = name,
        qualifiedName = qualifiedName,
        description = description,
        pluginName = qualifiedName.substringBefore(":"),
        pluginVersion = "1.0.0",
        filePath = Paths.get("/tmp/fake/$name/SKILL.md"),
        aliases = listOf("/$name", "/$qualifiedName"),
    )

    @Test
    fun `returns empty string for empty list`() {
        val result = CliProcess.buildSkillCatalogPrompt(emptyList())
        assertEquals("", result)
    }

    @Test
    fun `single skill produces one catalog line`() {
        val skills = listOf(skill("brainstorming", "superpowers:brainstorming", "Brainstorm ideas into designs"))
        val result = CliProcess.buildSkillCatalogPrompt(skills)
        assertTrue(result.contains("- superpowers:brainstorming: Brainstorm ideas into designs"))
    }

    @Test
    fun `multiple skills produce one line per skill`() {
        val skills = listOf(
            skill("brainstorming", "superpowers:brainstorming", "Brainstorm ideas"),
            skill("commit", "commit-commands:commit", "Create a git commit"),
        )
        val result = CliProcess.buildSkillCatalogPrompt(skills)
        assertTrue(result.contains("- superpowers:brainstorming: Brainstorm ideas"))
        assertTrue(result.contains("- commit-commands:commit: Create a git commit"))
    }

    @Test
    fun `output includes header and trailing instruction`() {
        val skills = listOf(skill("tdd", "superpowers:tdd", "Test-driven development"))
        val result = CliProcess.buildSkillCatalogPrompt(skills)
        assertTrue(result.startsWith("Available skills (invoke via slash command):"))
        assertTrue(result.contains("When a skill matches the user's task, suggest invoking it with /<skill-name>."))
    }
}
