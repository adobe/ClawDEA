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

import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.mcp.McpToolRouter
import com.adobe.clawdea.provider.openai.agent.AgentClient
import com.adobe.clawdea.provider.openai.agent.AgentCompletionRequest
import com.adobe.clawdea.provider.openai.agent.AgentStreamEvent
import com.adobe.clawdea.provider.openai.agent.AgentToolCall
import com.adobe.clawdea.provider.openai.agent.OpenAiToolDefinition
import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
import com.adobe.clawdea.provider.openai.session.OpenAiSessionLedger
import com.adobe.clawdea.provider.openai.tools.SharedToolApprovalGate
import com.adobe.clawdea.settings.ClawDEASettings
import com.adobe.clawdea.skills.SkillInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
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

    @Test
    fun `Skill with wrong-typed name returns error`() {
        val f = tempDir.newFile("SKILL.md")
        f.writeText("# Brainstorming\n\nSteps.")
        val executor = defaultExecutor(
            project = null,
            mcpDefs = emptyList(),
            approvalGate = SharedToolApprovalGate(
                toolApprovalMode = { "allow-all" },
                policy = { null },
                route = { _, _, _ -> null },
                promptTimeoutMs = 0,
            ),
            skills = listOf(skill(f.toPath())),
            autoAcceptEdits = { false },
        )

        val result = executor.execute(
            AgentToolCall(id = "c2", name = "Skill", argumentsJson = """{"name":{"nested":"obj"}}"""),
        )

        assertTrue(result.isError)
    }

    @Test
    fun `Skill with null name returns missing parameter error`() {
        val f = tempDir.newFile("SKILL.md")
        f.writeText("# Brainstorming\n\nSteps.")
        val executor = defaultExecutor(
            project = null,
            mcpDefs = emptyList(),
            approvalGate = SharedToolApprovalGate(
                toolApprovalMode = { "allow-all" },
                policy = { null },
                route = { _, _, _ -> null },
                promptTimeoutMs = 0,
            ),
            skills = listOf(skill(f.toPath())),
            autoAcceptEdits = { false },
        )

        val result = executor.execute(
            AgentToolCall(id = "c3", name = "Skill", argumentsJson = """{"name":null}"""),
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("missing required parameter: name"))
    }

    @Test
    fun `Skill with missing name returns missing parameter error`() {
        val f = tempDir.newFile("SKILL.md")
        f.writeText("# Brainstorming\n\nSteps.")
        val executor = defaultExecutor(
            project = null,
            mcpDefs = emptyList(),
            approvalGate = SharedToolApprovalGate(
                toolApprovalMode = { "allow-all" },
                policy = { null },
                route = { _, _, _ -> null },
                promptTimeoutMs = 0,
            ),
            skills = listOf(skill(f.toPath())),
            autoAcceptEdits = { false },
        )

        val result = executor.execute(
            AgentToolCall(id = "c4", name = "Skill", argumentsJson = """{}"""),
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("missing required parameter: name"))
    }

    @Test
    fun `Skill with malformed JSON returns malformed arguments error`() {
        val f = tempDir.newFile("SKILL.md")
        f.writeText("# Brainstorming\n\nSteps.")
        val executor = defaultExecutor(
            project = null,
            mcpDefs = emptyList(),
            approvalGate = SharedToolApprovalGate(
                toolApprovalMode = { "allow-all" },
                policy = { null },
                route = { _, _, _ -> null },
                promptTimeoutMs = 0,
            ),
            skills = listOf(skill(f.toPath())),
            autoAcceptEdits = { false },
        )

        val result = executor.execute(
            AgentToolCall(id = "c5", name = "Skill", argumentsJson = "not-json"),
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("Malformed Skill arguments"))
    }

    @Test
    fun `Skill with non-primitive args treats args as absent and executes`() {
        val f = tempDir.newFile("SKILL.md")
        f.writeText("# Brainstorming\n\nSteps.")
        val executor = defaultExecutor(
            project = null,
            mcpDefs = emptyList(),
            approvalGate = SharedToolApprovalGate(
                toolApprovalMode = { "allow-all" },
                policy = { null },
                route = { _, _, _ -> null },
                promptTimeoutMs = 0,
            ),
            skills = listOf(skill(f.toPath())),
            autoAcceptEdits = { false },
        )

        val result = executor.execute(
            AgentToolCall(id = "c6", name = "Skill", argumentsJson = """{"name":"superpowers:brainstorming","args":{"x":1}}"""),
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("# Brainstorming"))
    }

    @Test(timeout = 30_000)
    fun `Skill tool advertised on resume start with skills`() {
        val f = tempDir.newFile("SKILL.md")
        f.writeText("# Brainstorming\n\nSteps.")

        var capturedTools: List<Any>? = null
        val backend = newBackend(
            onRequest = { request ->
                capturedTools = request.tools
            }
        )

        // Resume-shaped start: non-null resumeSessionId, non-empty skills list.
        // The ledger won't have "resume-1" (doesn't matter; currentSkills is set before the resume check).
        backend.start(resumeSessionId = "resume-1", skills = listOf(skill(f.toPath())))

        // Drain SystemInit
        val init = backend.readEvent()
        assertTrue("start must emit SystemInit", init is CliEvent.SystemInit)

        // Send a message to trigger a request (which will populate capturedTools)
        backend.sendMessage("go")

        // Drain events until Result
        while (true) {
            val event = backend.readEvent() ?: break
            if (event is CliEvent.Result) break
        }

        // Assert the Skill tool was advertised in the request
        val toolNames = capturedTools?.map { (it as OpenAiToolDefinition).function.name }
        assertTrue("Skill tool must be advertised on resume start with skills", toolNames?.contains("Skill") == true)

        backend.stop()
    }

    private fun newBackend(
        onRequest: (AgentCompletionRequest) -> Unit = {},
    ): OpenAiCompatibleAgentBackend {
        val fakeClient = object : AgentClient {
            override suspend fun stream(request: AgentCompletionRequest): Flow<AgentStreamEvent> = flow {
                onRequest(request)
                emit(AgentStreamEvent.Text("done"))
                emit(AgentStreamEvent.Finished("stop"))
            }
        }
        return OpenAiCompatibleAgentBackend(
            profile = ResolvedProviderProfile(
                profile = OpenAiCompatibleProfile(id = "test-profile", name = "Test", baseUrl = "https://test"),
                baseUrl = URI("https://test"),
                configuredValues = emptyMap(),
            ),
            credentialProvider = { "test-key" },
            modelIdProvider = { "test-model" },
            project = null,
            projectPath = tempDir.root.canonicalPath,
            mcpDefs = emptyList(),
            approvalGate = SharedToolApprovalGate(
                toolApprovalMode = { "allow-all" },
                policy = { null },
                route = { _, _, _ -> null },
                promptTimeoutMs = 30_000,
            ),
            autoAcceptEdits = { false },
            fallbackAgentLabel = "Test Agent",
            ledger = OpenAiSessionLedger(tempDir.root.canonicalPath),
            clientFactory = { _, _ -> fakeClient },
            settingsProvider = { ClawDEASettings() },
        )
    }
}
