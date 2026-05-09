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
package com.adobe.clawdea.commands.handlers

import com.adobe.clawdea.commands.*
import com.adobe.clawdea.skills.SkillInfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

class SkillHandlerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun createSkillFile(content: String): Path {
        val file = tempDir.newFile("SKILL.md")
        file.writeText(content)
        return file.toPath()
    }

    private fun makeSkillInfo(filePath: Path): SkillInfo {
        return SkillInfo(
            name = "brainstorming",
            qualifiedName = "superpowers:brainstorming",
            description = "Brainstorm ideas",
            pluginName = "superpowers",
            pluginVersion = "5.0.7",
            filePath = filePath,
            aliases = listOf("/brainstorming", "/superpowers:brainstorming"),
        )
    }

    @Test
    fun `buildFallbackMessage wraps skill content with command tags`() {
        val skillContent = """
            ---
            name: brainstorming
            description: Brainstorm ideas
            ---

            # Brainstorming

            Some instructions here.
        """.trimIndent()

        val filePath = createSkillFile(skillContent)
        val skillInfo = makeSkillInfo(filePath)

        val message = SkillHandler.buildFallbackMessage(skillInfo, "add drag-and-drop")

        assertTrue(message.contains("<command-name>superpowers:brainstorming</command-name>"))
        assertTrue(message.contains("<command-args>add drag-and-drop</command-args>"))
        assertTrue(message.contains("# Brainstorming"))
        assertTrue(message.contains("ARGUMENTS: add drag-and-drop"))
    }

    @Test
    fun `buildFallbackMessage handles empty args`() {
        val skillContent = """
            ---
            name: brainstorming
            description: Brainstorm ideas
            ---

            # Brainstorming
        """.trimIndent()

        val filePath = createSkillFile(skillContent)
        val skillInfo = makeSkillInfo(filePath)

        val message = SkillHandler.buildFallbackMessage(skillInfo, "")

        assertTrue(message.contains("<command-name>superpowers:brainstorming</command-name>"))
        assertTrue(message.contains("<command-args></command-args>"))
        assertTrue(message.contains("ARGUMENTS: "))
    }

    @Test
    fun `SkillHandler has correct info`() {
        val filePath = createSkillFile("---\nname: test\n---")
        val skillInfo = makeSkillInfo(filePath)
        val handler = SkillHandler(skillInfo, sendToBridge = {}, probeResult = { true })

        assertEquals(CommandCategory.SKILL, handler.info.category)
        assertEquals("/brainstorming", handler.info.name)
    }
}
