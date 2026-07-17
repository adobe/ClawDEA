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
import com.adobe.clawdea.provider.openai.agent.AgentStreamEvent.Finished
import com.adobe.clawdea.provider.openai.agent.AgentStreamEvent.Text
import com.adobe.clawdea.provider.openai.agent.AgentStreamEvent.ToolFragment
import com.adobe.clawdea.provider.openai.agent.AgentToolCall
import com.adobe.clawdea.provider.openai.agent.AgentToolExecutor
import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
import com.adobe.clawdea.provider.openai.session.OpenAiSessionLedger
import com.adobe.clawdea.provider.openai.tools.SharedToolApprovalGate
import com.adobe.clawdea.provider.openai.tools.ToolExecutionResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class OpenAiCompatibleAgentBackendSteeringTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `steer preserves text discards incomplete tool and continues without terminal result`() = runTest {
        val backend = steeringHarness(
            firstStream = listOf(Text("partial"), ToolFragment(0, "call-1", "shell", """{"command":""")),
            continuation = listOf(Text(" revised"), Finished("stop")),
        )
        backend.sendMessage("start")
        assertTrue(backend.steer("change direction"))

        assertEquals(listOf("partial", " revised"), backend.eventsOfType<CliEvent.TextDelta>().map { it.text })
        assertFalse(backend.executedToolIds.contains("call-1"))
        assertEquals(1, backend.eventsOfType<CliEvent.Result>().size)
    }

    @Test
    fun `steer returns false when no turn is active`() {
        val harness = SteeringHarness(tempFolder)
        harness.startFresh()
        assertFalse(harness.steer("nothing running"))
        harness.stop()
    }

    private fun steeringHarness(
        firstStream: List<AgentStreamEvent>,
        continuation: List<AgentStreamEvent>,
    ): SteeringHarness {
        val harness = SteeringHarness(tempFolder)
        harness.configureStreams(firstStream, continuation)
        harness.startFresh()
        return harness
    }

    /**
     * Test harness around [OpenAiCompatibleAgentBackend] driving cancel-and-continue steering with
     * canned stream flows. [sendMessage] blocks until the first stream has emitted its events and is
     * suspended, so a subsequent [steer] deterministically hits an active mid-stream round.
     */
    class SteeringHarness(tempFolder: TemporaryFolder) {
        private val streamCallCount = AtomicInteger(0)
        private val firstStreamReady = CountDownLatch(1)
        private var firstStream: List<AgentStreamEvent> = emptyList()
        private var continuation: List<AgentStreamEvent> = emptyList()
        val executedToolIds = mutableListOf<String>()

        private var cachedEvents: List<CliEvent>? = null

        private val fakeClient = object : AgentClient {
            override suspend fun stream(request: AgentCompletionRequest): Flow<AgentStreamEvent> {
                val call = streamCallCount.getAndIncrement()
                return if (call == 0) {
                    flow {
                        firstStream.forEach { emit(it) }
                        // Signal the harness that the first round is mid-stream, then suspend so
                        // steer() can cancel this round while it is still "in progress".
                        firstStreamReady.countDown()
                        awaitCancellation()
                    }
                } else {
                    flow { continuation.forEach { emit(it) } }
                }
            }
        }

        private val fakeExecutor = object : AgentToolExecutor {
            override fun execute(toolCall: AgentToolCall): ToolExecutionResult {
                executedToolIds.add(toolCall.id)
                return ToolExecutionResult(toolCall.id, "ok", false)
            }
        }

        private val backend = OpenAiCompatibleAgentBackend(
            profile = ResolvedProviderProfile(
                profile = OpenAiCompatibleProfile(id = "test-profile", name = "Test", baseUrl = "https://test"),
                baseUrl = URI("https://test"),
                configuredValues = emptyMap(),
            ),
            credentialProvider = { "test-key" },
            modelId = "test-model",
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
        )

        fun configureStreams(first: List<AgentStreamEvent>, cont: List<AgentStreamEvent>) {
            firstStream = first
            continuation = cont
        }

        fun startFresh() {
            backend.start(null, emptyList())
            backend.readEvent() // drain SystemInit
        }

        fun sendMessage(text: String) {
            backend.sendMessage(text)
            // Block until the first round is mid-stream and suspended.
            firstStreamReady.await()
        }

        fun steer(text: String): Boolean = backend.steer(text)

        fun stop() = backend.stop()

        fun collectedEvents(): List<CliEvent> {
            cachedEvents?.let { return it }
            val collected = mutableListOf<CliEvent>()
            while (true) {
                val event = backend.readEvent() ?: break
                collected.add(event)
                if (event is CliEvent.Result) break
            }
            cachedEvents = collected
            return collected
        }

        inline fun <reified T : CliEvent> eventsOfType(): List<T> =
            collectedEvents().filterIsInstance<T>()
    }
}
