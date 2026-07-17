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
import com.adobe.clawdea.mcp.McpToolRouter
import com.adobe.clawdea.provider.openai.agent.AgentToolCall
import com.adobe.clawdea.provider.openai.agent.AgentToolExecutor
import com.adobe.clawdea.provider.openai.fixture.OpenAiCompatibleFixtureServer
import com.adobe.clawdea.provider.openai.fixture.disconnectAfterPartial
import com.adobe.clawdea.provider.openai.fixture.finalText
import com.adobe.clawdea.provider.openai.fixture.interleavedToolCalls
import com.adobe.clawdea.provider.openai.fixture.nonStreamedText
import com.adobe.clawdea.provider.openai.fixture.nonStreamedToolCall
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
        val harness = harness(
            fixture.profile(),
            model = "model-agentic",
            mcpDefs = listOf(mcpDef("find_files")),
        )
        harness.start()
        harness.sendAndDrain("find files")

        assertTrue(harness.events.any { it is CliEvent.ToolResult })
        assertEquals("done", harness.textDeltas().joinToString(""))

        // Regression guard for the "tools never advertised" defect: the outgoing request MUST carry
        // a tools array naming the MCP tool PLUS the host Bash/apply_patch tools the executor can
        // dispatch. Before tools were wired into the request this assertion would have failed even
        // though the (unconditional) fixture still emitted tool_calls.
        val advertised = advertisedToolNames(fixture.requestBodies.first())
        assertEquals(setOf("find_files", "Bash", "apply_patch"), advertised)

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
    fun `non-streamed JSON completion yields non-empty text over real HTTP`() {
        // Gateway ignores stream:true and returns a single application/json chat.completion object
        // (payload in choices[0].message.*, no SSE framing). The client must auto-detect and parse it.
        fixture.script(nonStreamedText("hello from json"))
        val harness = harness(fixture.profile(), model = "model-agentic")
        harness.start()
        harness.sendAndDrain("hi")

        assertEquals("hello from json", harness.textDeltas().joinToString(""))
        assertEquals(1, harness.results().size)
        assertTrue(!harness.results().single().isError)
        assertEquals("hello from json", harness.results().single().text)
        harness.stop()
    }

    @Test
    fun `streaming true request body carries stream true and stream_options include_usage`() {
        fixture.script(finalText("streamed"))
        val harness = harness(fixture.profile(streaming = true), model = "model-agentic")
        harness.start()
        harness.sendAndDrain("go")

        val body = com.google.gson.JsonParser.parseString(fixture.requestBodies.first()).asJsonObject
        assertTrue(body.get("stream").asBoolean)
        assertTrue(body.has("stream_options"))
        assertTrue(body.getAsJsonObject("stream_options").get("include_usage").asBoolean)
        harness.stop()
    }

    @Test
    fun `streaming false sends stream false without stream_options and still yields text`() {
        // A streaming=false profile must request stream:false; the client parses the fixture's single
        // JSON completion via parseNonStreamedCompletion (no client change needed — auto-detected).
        fixture.script(nonStreamedText("no-stream answer"))
        val harness = harness(fixture.profile(streaming = false), model = "model-agentic")
        harness.start()
        harness.sendAndDrain("go")

        val body = com.google.gson.JsonParser.parseString(fixture.requestBodies.first()).asJsonObject
        assertTrue(!body.get("stream").asBoolean)
        assertTrue("stream_options must be omitted when not streaming", !body.has("stream_options"))

        assertEquals("no-stream answer", harness.textDeltas().joinToString(""))
        assertEquals(1, harness.results().size)
        assertTrue(!harness.results().single().isError)
        harness.stop()
    }

    @Test
    fun `non-streamed tool call drives exactly one tool round then completes`() {
        fixture.script(nonStreamedToolCall("find_files"), nonStreamedText("done"))
        val harness = harness(
            fixture.profile(),
            model = "model-agentic",
            mcpDefs = listOf(mcpDef("find_files")),
        )
        harness.start()
        harness.sendAndDrain("find files")

        val toolResults = harness.events.filterIsInstance<CliEvent.ToolResult>()
        assertEquals(1, toolResults.size)
        assertEquals("call_1", toolResults.single().toolUseId)
        assertEquals("done", harness.textDeltas().joinToString(""))
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

    // --- helpers ---

    private fun mcpDef(name: String): McpToolRouter.ToolDef = McpToolRouter.ToolDef(
        name = name,
        description = "Test tool $name",
        properties = listOf(Triple("q", "string", "query")),
        required = emptyList(),
        handler = { McpToolRouter.ToolResult("ok($name)") },
    )

    /** Parse the `tools[].function.name` set advertised in an outgoing `/chat/completions` body. */
    private fun advertisedToolNames(requestBody: String): Set<String> {
        val root = com.google.gson.JsonParser.parseString(requestBody).asJsonObject
        val tools = root.getAsJsonArray("tools") ?: return emptySet()
        return tools.map { it.asJsonObject.getAsJsonObject("function").get("name").asString }.toSet()
    }

    // --- harness ---

    private fun harness(
        profile: ResolvedProviderProfile,
        model: String,
        renewer: () -> Boolean = { false },
        mcpDefs: List<McpToolRouter.ToolDef> = emptyList(),
        useRealExecutor: Boolean = mcpDefs.isNotEmpty(),
    ) = Harness(profile, model, renewer, mcpDefs, useRealExecutor)

    private inner class Harness(
        profile: ResolvedProviderProfile,
        model: String,
        renewer: () -> Boolean,
        mcpDefs: List<McpToolRouter.ToolDef>,
        useRealExecutor: Boolean,
    ) {
        val events = mutableListOf<CliEvent>()

        private val approvalGate = SharedToolApprovalGate(
            toolApprovalMode = { "allow-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 30_000,
        )

        private val fakeExecutor = object : AgentToolExecutor {
            override fun execute(toolCall: AgentToolCall): ToolExecutionResult =
                ToolExecutionResult(toolCall.id, "ok(${toolCall.name})", false)
        }

        // Real client via the production default clientFactory (no override) — real HTTP + SSE.
        // When [useRealExecutor] is set we drive the production dispatch path (project=null, so host
        // tools are unavailable and MCP tools route through the real catalog) instead of a fake.
        private val backend = OpenAiCompatibleAgentBackend(
            profile = profile,
            credentialProvider = { "fixture-key" },
            modelIdProvider = { model },
            project = null,
            projectPath = projectPath,
            mcpDefs = mcpDefs,
            approvalGate = approvalGate,
            autoAcceptEdits = { false },
            agentLabel = "Fixture Provider",
            ledger = OpenAiSessionLedger(projectPath),
            executorFactory = if (useRealExecutor) {
                { com.adobe.clawdea.cli.backend.defaultExecutor(null, mcpDefs, approvalGate) { false } }
            } else {
                { fakeExecutor }
            },
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
