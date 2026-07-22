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
package com.adobe.clawdea.provider.openai.tools

import com.adobe.clawdea.skills.SkillInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

class SkillToolTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun skillFile(content: String, fileName: String = "SKILL.md"): Path {
        val f = tempDir.newFile(fileName)
        f.writeText(content)
        return f.toPath()
    }

    private fun skill(filePath: Path) = SkillInfo(
        name = "brainstorming",
        qualifiedName = "superpowers:brainstorming",
        description = "Brainstorm ideas",
        pluginName = "superpowers",
        pluginVersion = "5.0.7",
        filePath = filePath,
        aliases = listOf("/brainstorming", "/superpowers:brainstorming"),
    )

    @Test
    fun `resolves by qualified name and returns skill content`() {
        val fp = skillFile("---\nname: brainstorming\n---\n\n# Brainstorming\n\nSteps.")
        val tool = SkillTool(listOf(skill(fp)))

        val result = tool.execute("superpowers:brainstorming", "make X", "call-1")

        assertFalse(result.isError)
        assertEquals("call-1", result.toolCallId)
        assertTrue(result.content.contains("<command-name>superpowers:brainstorming</command-name>"))
        assertTrue(result.content.contains("<command-args>make X</command-args>"))
        assertTrue(result.content.contains("# Brainstorming"))
    }

    @Test
    fun `resolves by short name`() {
        val fp = skillFile("# Brainstorming")
        val tool = SkillTool(listOf(skill(fp)))
        val result = tool.execute("brainstorming", null, "call-2")
        assertFalse(result.isError)
        assertTrue(result.content.contains("# Brainstorming"))
    }

    @Test
    fun `resolves by alias tolerating leading slash and case`() {
        val fp = skillFile("# Brainstorming")
        val tool = SkillTool(listOf(skill(fp)))
        val result = tool.execute("/Brainstorming", null, "call-3")
        assertFalse(result.isError)
        assertTrue(result.content.contains("# Brainstorming"))
    }

    @Test
    fun `unknown name errors and lists available skills`() {
        val fp = skillFile("# Brainstorming")
        val tool = SkillTool(listOf(skill(fp)))
        val result = tool.execute("nonexistent", null, "call-4")
        assertTrue(result.isError)
        assertTrue(result.content.contains("superpowers:brainstorming"))
    }

    @Test
    fun `unreadable file errors without throwing`() {
        val missing = tempDir.root.toPath().resolve("gone/SKILL.md")
        val info = skill(missing) // path does not exist
        val tool = SkillTool(listOf(info))
        val result = tool.execute("superpowers:brainstorming", null, "call-5")
        assertTrue(result.isError)
    }
}
