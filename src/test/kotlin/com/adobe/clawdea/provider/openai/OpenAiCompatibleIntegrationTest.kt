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
package com.adobe.clawdea.provider.openai

import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.cli.backend.OpenAiCompatibleAgentBackend
import com.adobe.clawdea.provider.openai.agent.AgentToolCall
import com.adobe.clawdea.provider.openai.agent.AgentToolExecutor
import com.adobe.clawdea.provider.openai.fixture.OpenAiCompatibleFixtureServer
import com.adobe.clawdea.provider.openai.fixture.disconnectAfterPartial
import com.adobe.clawdea.provider.openai.fixture.finalText
import com.adobe.clawdea.provider.openai.fixture.interleavedToolCalls
import com.adobe.clawdea.provider.openai.fixture.reasoningThenText
import com.adobe.clawdea.provider.openai.fixture.serverError
import com.adobe.clawdea.provider.openai.fixture.toolCall
import com.adobe.clawdea.provider.openai.fixture.unauthorized
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
import com.adobe.clawdea.provider.openai.session.OpenAiSessionLedger
import com.adobe.clawdea.provider.openai.session.OpenAiSessionScanner
import com.adobe.clawdea.provider.openai.tools.SharedToolApprovalGate
import com.adobe.clawdea.provider.openai.tools.ToolExecutionResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end tests driving the real [OpenAiCompatibleAgentBackend] +
 * [com.adobe.clawdea.provider.openai.client.OpenAiCompatibleClient] over real HTTP against the
 * in-process [OpenAiCompatibleFixtureServer]. This is the real network/SSE path the earlier
 * unit-fixture tasks deferred to Task 8: no client fake, real socket, real SSE parsing.
 *
 * `user.home` is redirected to a temp dir so session ledgers land there (never the real home or
 * the repo), and so [OpenAiSessionScanner.scan] resolves the same root the backend writes to.
 */
class OpenAiCompatibleIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var fixture: OpenAiCompatibleFixtureServer
    private lateinit var projectPath: String
    private var originalHome: String? = null

    @Before
    fun setUp() {
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", temp.newFolder("home").canonicalPath)
        projectPath = temp.newFolder("project").canonicalPath
        fixture = OpenAiCompatibleFixtureServer()
    }

    @After
    fun tearDown() {
        fixture.stop()
        originalHome?.let { System.setProperty("user.home", it) }
    }

    @Test
    fun `agentic profile completes a tool round and persists resumable history`() {
        fixture.script(toolCall("find_files"), finalText("done"))
        val harness = harness(fixture.profile(), model = "model-agentic")
        harness.start()
        harness.sendAndDrain("find files")

        assertTrue(harness.events.any { it is CliEvent.ToolResult })
        assertEquals("done", harness.textDeltas().joinToString(""))
        val sessions = OpenAiSessionScanner.scan(projectPath)
        assertEquals(1, sessions.size)
        assertEquals("fixture-profile", sessions.single().profileId)
        harness.stop()
    }

    @Test
    fun `reasoning stream surfaces a reasoning delta then text over real HTTP`() {
        fixture.script(reasoningThenText("thinking about it", "the answer"))
        val harness = harness(fixture.profile(), model = "model-agentic")
        harness.start()
        harness.sendAndDrain("why")

        val reasoning = harness.events.filterIsInstance<CliEvent.ReasoningDelta>()
        assertTrue(reasoning.any { it.text.contains("thinking about it") })
        assertEquals("the answer", harness.textDeltas().joinToString(""))
        harness.stop()
    }

    @Test
    fun `interleaved tool-call fragments assemble into two executed tools`() {
        fixture.script(
            interleavedToolCalls(
                Triple("find_files", """{"q":"a"}""", "call_a"),
                Triple("find_files", """{"q":"b"}""", "call_b"),
            ),
            finalText("both done"),
        )
        val harness = harness(fixture.profile(), model = "model-agentic")
        harness.start()
        harness.sendAndDrain("do both")

        val toolResults = harness.events.filterIsInstance<CliEvent.ToolResult>()
        assertEquals(setOf("call_a", "call_b"), toolResults.map { it.toolUseId }.toSet())
        assertEquals("both done", harness.textDeltas().joinToString(""))
        harness.stop()
    }

    @Test
    fun `500 before output auto-retries over real HTTP then succeeds`() {
        fixture.script(serverError(), finalText("recovered"))
        val harness = harness(fixture.profile(), model = "model-agentic")
        harness.start()
        harness.sendAndDrain("go")

        assertEquals("recovered", harness.textDeltas().joinToString(""))
        assertEquals(1, harness.results().size)
        assertTrue(!harness.results().single().isError)
        harness.stop()
    }

    @Test
    fun `401 without renewal surfaces a terminal error over real HTTP`() {
        fixture.script(unauthorized())
        val harness = harness(fixture.profile(), model = "model-agentic", renewer = { false })
        harness.start()
        harness.sendAndDrain("go")

        assertEquals(1, harness.results().size)
        assertTrue(harness.results().single().isError)
        harness.stop()
    }

    @Test
    fun `disconnect after partial output preserves partial text and ends the turn`() {
        fixture.script(disconnectAfterPartial("half a thought"))
        // project == null so the partial-retry prompt fails closed → terminal error.
        val harness = harness(fixture.profile(), model = "model-agentic")
        harness.start()
        harness.sendAndDrain("go")

        assertTrue(harness.textDeltas().joinToString("").contains("half a thought"))
        assertTrue(harness.results().single().isError)
        harness.stop()
    }

    // --- harness ---

    private fun harness(
        profile: ResolvedProviderProfile,
        model: String,
        renewer: () -> Boolean = { false },
    ) = Harness(profile, model, renewer)

    private inner class Harness(
        profile: ResolvedProviderProfile,
        model: String,
        renewer: () -> Boolean,
    ) {
        val events = mutableListOf<CliEvent>()

        private val executor = object : AgentToolExecutor {
            override fun execute(toolCall: AgentToolCall): ToolExecutionResult =
                ToolExecutionResult(toolCall.id, "ok(${toolCall.name})", false)
        }

        // Real client via the production default clientFactory (no override) — real HTTP + SSE.
        private val backend = OpenAiCompatibleAgentBackend(
            profile = profile,
            credential = "fixture-key",
            modelId = model,
            project = null,
            projectPath = projectPath,
            mcpDefs = emptyList(),
            approvalGate = SharedToolApprovalGate(
                toolApprovalMode = { "allow-all" },
                policy = { null },
                route = { _, _, _ -> null },
                promptTimeoutMs = 30_000,
            ),
            autoAcceptEdits = { false },
            agentLabel = "Fixture Provider",
            ledger = OpenAiSessionLedger(projectPath),
            executorFactory = { executor },
            credentialRenewer = renewer,
        )

        fun start() {
            backend.start(null, emptyList())
            backend.readEvent() // SystemInit
        }

        fun sendAndDrain(text: String) {
            backend.sendMessage(text)
            while (true) {
                val event = backend.readEvent() ?: break
                events.add(event)
                if (event is CliEvent.Result) break
            }
        }

        fun textDeltas(): List<String> = events.filterIsInstance<CliEvent.TextDelta>().map { it.text }
        fun results(): List<CliEvent.Result> = events.filterIsInstance<CliEvent.Result>()
        fun stop() = backend.stop()
    }
}
