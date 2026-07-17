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
import com.adobe.clawdea.provider.openai.agent.AgentToolExecutor
import com.adobe.clawdea.provider.openai.agent.AgentToolCall
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
import java.util.concurrent.atomic.AtomicInteger

class OpenAiCompatibleAgentBackendRetryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `5xx before output auto-retries then succeeds with one terminal result`() {
        val callCount = AtomicInteger(0)
        val harness = RetryHarness(tempFolder, credentialRenewer = { false }) {
            if (callCount.getAndIncrement() == 0) {
                flow { emit(AgentStreamEvent.Failure(status = 503, message = "server error", retryAfterSeconds = null)) }
            } else {
                flow {
                    emit(AgentStreamEvent.Text("done"))
                    emit(AgentStreamEvent.Finished("stop"))
                }
            }
        }
        harness.start()

        harness.sendAndDrain("go")

        assertEquals(2, callCount.get())
        assertEquals(listOf("done"), harness.textDeltas())
        val results = harness.results()
        assertEquals(1, results.size)
        assertFalse(results.single().isError)
        harness.stop()
    }

    @Test
    fun `401 without renewal emits one terminal auth error`() {
        val callCount = AtomicInteger(0)
        val harness = RetryHarness(tempFolder, credentialRenewer = { false }) {
            callCount.incrementAndGet()
            flow { emit(AgentStreamEvent.Failure(status = 401, message = "unauthorized", retryAfterSeconds = null)) }
        }
        harness.start()

        harness.sendAndDrain("go")

        // One attempt: policy says RenewCredentialOnce, renewer fails, terminal error emitted.
        assertEquals(1, callCount.get())
        val results = harness.results()
        assertEquals(1, results.size)
        assertTrue(results.single().isError)
        harness.stop()
    }

    class RetryHarness(
        tempFolder: TemporaryFolder,
        credentialRenewer: () -> Boolean,
        private val streamFactory: () -> Flow<AgentStreamEvent>,
    ) {
        private val collected = mutableListOf<CliEvent>()

        private val fakeClient = object : AgentClient {
            override suspend fun stream(request: AgentCompletionRequest): Flow<AgentStreamEvent> = streamFactory()
        }

        private val fakeExecutor = object : AgentToolExecutor {
            override fun execute(toolCall: AgentToolCall): ToolExecutionResult =
                ToolExecutionResult(toolCall.id, "ok", false)
        }

        private val backend = OpenAiCompatibleAgentBackend(
            profile = ResolvedProviderProfile(
                profile = OpenAiCompatibleProfile(id = "test-profile", name = "Test", baseUrl = "https://test"),
                baseUrl = URI("https://test"),
                configuredValues = emptyMap(),
            ),
            credentialProvider = { "test-key" },
            modelIdProvider = { "test-model" },
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
            agentLabel = "Test Agent",
            ledger = OpenAiSessionLedger(tempFolder.root.canonicalPath),
            clientFactory = { _, _ -> fakeClient },
            executorFactory = { fakeExecutor },
            credentialRenewer = credentialRenewer,
        )

        fun start() {
            backend.start(null, emptyList())
            backend.readEvent() // SystemInit
        }

        fun sendAndDrain(text: String) {
            backend.sendMessage(text)
            while (true) {
                val event = backend.readEvent() ?: break
                collected.add(event)
                if (event is CliEvent.Result) break
            }
        }

        fun textDeltas(): List<String> = collected.filterIsInstance<CliEvent.TextDelta>().map { it.text }
        fun results(): List<CliEvent.Result> = collected.filterIsInstance<CliEvent.Result>()
        fun stop() = backend.stop()
    }
}
