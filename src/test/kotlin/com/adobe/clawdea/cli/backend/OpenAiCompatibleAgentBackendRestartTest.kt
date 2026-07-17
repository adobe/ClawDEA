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
import com.adobe.clawdea.provider.openai.agent.AgentClient
import com.adobe.clawdea.provider.openai.agent.AgentCompletionRequest
import com.adobe.clawdea.provider.openai.agent.AgentStreamEvent
import com.adobe.clawdea.provider.openai.agent.AgentToolCall
import com.adobe.clawdea.provider.openai.agent.AgentToolExecutor
import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
import com.adobe.clawdea.provider.openai.session.OpenAiSessionLedger
import com.adobe.clawdea.provider.openai.tools.SharedToolApprovalGate
import com.adobe.clawdea.provider.openai.tools.ToolExecutionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI

/**
 * Regression guard for the lifecycle bug where [OpenAiCompatibleAgentBackend] became permanently
 * dead after a stop→start cycle (every chat then showed "CLI process exited unexpectedly").
 *
 * CliBridge constructs the backend once and reuses it across stop()/start(); the HTTP backend must
 * therefore be fully restartable — [start] returns it to a live state with a working scope + queue.
 */
class OpenAiCompatibleAgentBackendRestartTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `freshly constructed backend is not alive until start`() {
        val backend = newBackend()
        assertFalse("backend must not be alive before start()", backend.isAlive)
        backend.start(null, emptyList())
        assertTrue("backend must be alive after start()", backend.isAlive)
        backend.stop()
    }

    @Test
    fun `backend is restartable across stop then start`() {
        val backend = newBackend()

        // First start: alive, SystemInit emitted.
        backend.start(null, emptyList())
        assertTrue(backend.isAlive)
        val firstInit = backend.readEvent()
        assertTrue("first start must emit SystemInit", firstInit is CliEvent.SystemInit)

        // Stop: readEvent returns null (EOF), backend not alive.
        backend.stop()
        assertFalse(backend.isAlive)
        assertEquals("stop() must signal EOF", null, backend.readEvent())

        // Start again on the SAME instance: must return to a live, usable state.
        backend.start(null, emptyList())
        assertTrue("restart must revive the backend", backend.isAlive)
        val secondInit = backend.readEvent()
        assertTrue("restart must emit a fresh SystemInit", secondInit is CliEvent.SystemInit)

        // And a turn after restart must actually run (not "sendMessage on stopped backend").
        backend.sendMessage("go")
        val collected = mutableListOf<CliEvent>()
        while (true) {
            val event = backend.readEvent() ?: break
            collected.add(event)
            if (event is CliEvent.Result) break
        }
        val textDeltas = collected.filterIsInstance<CliEvent.TextDelta>().map { it.text }
        assertEquals("turn after restart must stream output", listOf("done"), textDeltas)
        val results = collected.filterIsInstance<CliEvent.Result>()
        assertEquals(1, results.size)
        assertFalse("turn after restart must succeed", results.single().isError)

        backend.stop()
    }

    @Test
    fun `backend uses the current model id from the provider on each start`() {
        // Regression guard for the frozen-model bug: switching the model in the chat dropdown
        // restarts the reused backend instance; start() must re-read the current selection so the
        // SystemInit model (which drives the footer + request body) reflects the new model.
        var selectedModel = "model-a"
        val backend = newBackend(modelIdProvider = { selectedModel })

        backend.start(null, emptyList())
        val firstInit = backend.readEvent() as CliEvent.SystemInit
        assertEquals("first start uses the initial model", "model-a", firstInit.model)
        backend.stop()
        assertEquals(null, backend.readEvent())

        // Simulate a dropdown switch: the provider now returns a different model.
        selectedModel = "model-b"
        backend.start(null, emptyList())
        val secondInit = backend.readEvent() as CliEvent.SystemInit
        assertEquals("restart must pick up the newly-selected model", "model-b", secondInit.model)

        backend.stop()
    }

    @Test
    fun `backend keeps last known model when provider returns blank at start`() {
        var selectedModel = "model-a"
        val backend = newBackend(modelIdProvider = { selectedModel })

        backend.start(null, emptyList())
        val firstInit = backend.readEvent() as CliEvent.SystemInit
        assertEquals("model-a", firstInit.model)
        backend.stop()
        assertEquals(null, backend.readEvent())

        // Blank should not clobber the last known model (defensive; readiness gate normally prevents).
        selectedModel = ""
        backend.start(null, emptyList())
        val secondInit = backend.readEvent() as CliEvent.SystemInit
        assertEquals("blank provider keeps last known model", "model-a", secondInit.model)

        backend.stop()
    }

    @Test
    fun `agentLabel reflects the current model trimmed after start`() {
        var selectedModel = "hosted_vllm/x/Foo-7B"
        val backend = newBackend(modelIdProvider = { selectedModel })

        // Before start: no model resolved yet -> fall back to the provider label.
        assertEquals("Test Agent", backend.agentLabel)

        backend.start(null, emptyList())
        assertEquals("Foo-7B", backend.agentLabel)
        backend.stop()

        // A dropdown switch restarts the reused backend; the label must follow.
        selectedModel = "another/Bar-13B"
        backend.start(null, emptyList())
        assertEquals("Bar-13B", backend.agentLabel)
        backend.stop()
    }

    @Test
    fun `agentLabel falls back to provider label when model is blank`() {
        val backend = newBackend(modelIdProvider = { "" })
        assertEquals("Test Agent", backend.agentLabel)
        backend.start(null, emptyList())
        // Blank provider keeps last known (still blank) -> fallback label.
        assertEquals("Test Agent", backend.agentLabel)
        backend.stop()
    }

    private fun newBackend(
        modelIdProvider: () -> String = { "test-model" },
    ): OpenAiCompatibleAgentBackend {
        val fakeClient = object : AgentClient {
            override suspend fun stream(request: AgentCompletionRequest): Flow<AgentStreamEvent> = flow {
                emit(AgentStreamEvent.Text("done"))
                emit(AgentStreamEvent.Finished("stop"))
            }
        }
        val fakeExecutor = object : AgentToolExecutor {
            override fun execute(toolCall: AgentToolCall): ToolExecutionResult =
                ToolExecutionResult(toolCall.id, "ok", false)
        }
        return OpenAiCompatibleAgentBackend(
            profile = ResolvedProviderProfile(
                profile = OpenAiCompatibleProfile(id = "test-profile", name = "Test", baseUrl = "https://test"),
                baseUrl = URI("https://test"),
                configuredValues = emptyMap(),
            ),
            credentialProvider = { "test-key" },
            modelIdProvider = modelIdProvider,
            project = null,
            projectPath = tempFolder.root.canonicalPath,
            mcpDefs = emptyList(),
            approvalGate = SharedToolApprovalGate(
                toolApprovalMode = { "allow-all" },
                policy = { null },
                route = { _, _, _ -> null },
                promptTimeoutMs = 30_000,
            ),
            autoAcceptEdits = { false },
            fallbackAgentLabel = "Test Agent",
            ledger = OpenAiSessionLedger(tempFolder.root.canonicalPath),
            clientFactory = { _, _ -> fakeClient },
            executorFactory = { fakeExecutor },
        )
    }
}
