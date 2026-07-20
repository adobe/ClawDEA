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
package com.adobe.clawdea.cli.backend

import com.adobe.clawdea.mcp.McpToolRouter
import com.adobe.clawdea.provider.openai.agent.AgentToolCall
import com.adobe.clawdea.skills.SkillInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

class OpenAiCompatibleAgentBackendSkillToolTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun skill(filePath: Path) = SkillInfo(
        name = "brainstorming",
        qualifiedName = "superpowers:brainstorming",
        description = "Brainstorm",
        pluginName = "superpowers",
        pluginVersion = "1.0.0",
        filePath = filePath,
        aliases = listOf("/brainstorming"),
    )

    @Test
    fun `Skill tool advertised when enabled and skills present`() {
        val f = tempDir.newFile("SKILL.md").toPath()
        val defs = agentToolDefinitions(emptyList(), listOf(skill(f)), advertiseSkillTool = true)
        assertTrue(defs.any { it.function.name == "Skill" })
    }

    @Test
    fun `Skill tool not advertised when disabled`() {
        val f = tempDir.newFile("SKILL.md").toPath()
        val defs = agentToolDefinitions(emptyList(), listOf(skill(f)), advertiseSkillTool = false)
        assertFalse(defs.any { it.function.name == "Skill" })
    }

    @Test
    fun `Skill tool not advertised when no skills`() {
        val defs = agentToolDefinitions(emptyList(), emptyList(), advertiseSkillTool = true)
        assertFalse(defs.any { it.function.name == "Skill" })
    }

    @Test
    fun `executor routes Skill calls to SkillTool`() {
        val f = tempDir.newFile("SKILL.md")
        f.writeText("# Brainstorming\n\nSteps.")
        val executor = defaultExecutor(
            project = null,
            mcpDefs = emptyList(),
            approvalGate = com.adobe.clawdea.provider.openai.tools.SharedToolApprovalGate(
                toolApprovalMode = { "allow-all" },
                policy = { null },
                route = { _, _, _ -> null },
                promptTimeoutMs = 0,
            ),
            skills = listOf(skill(f.toPath())),
            autoAcceptEdits = { false },
        )

        val result = executor.execute(
            AgentToolCall(id = "c1", name = "Skill", argumentsJson = """{"name":"superpowers:brainstorming"}"""),
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("# Brainstorming"))
    }
}
