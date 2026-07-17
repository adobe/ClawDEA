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
package com.adobe.clawdea.knowledge.drift

import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.provider.openai.agent.AgentClient
import com.adobe.clawdea.provider.openai.agent.AgentCompletionRequest
import com.adobe.clawdea.provider.openai.agent.AgentStreamEvent
import com.adobe.clawdea.provider.openai.agent.AgentToolCall
import com.adobe.clawdea.provider.openai.agent.AgentToolExecutor
import com.adobe.clawdea.provider.openai.agent.OpenAiToolDefinition
import com.adobe.clawdea.provider.openai.catalog.ModelCapability
import com.adobe.clawdea.provider.openai.tools.ToolExecutionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Paths

class WikiAuthorInvokerSelectionTest {

    private val sampleEvents = listOf(
        DriftEvent.CommitDrift(
            wikiPage = Paths.get(".claude/wiki/concepts/x.md"),
            commitShas = listOf("abc"),
            touchedPaths = listOf("src/main/kotlin/Foo.kt"),
            firstObservedAt = "2026-05-17T16:30:00Z",
        ),
        DriftEvent.WikiSuggestion(
            kind = SuggestionKind.missingConcept,
            title = "t", rationale = "r",
            targetFiles = listOf(".claude/wiki/concepts/y.md"),
            sourcePage = null, recordedAt = "2026-05-17T16:30:00Z",
        ),
    )
    private val signatures = sampleEvents.map { it.signature }.toSet()

    // ---- (a) routing ----

    @Test fun `chooseWikiInvoker routes Claude to DefaultWikiAuthorInvoker that still passes --agents`() = runBlocking {
        val runner = StubProcessRunner(exitCode = 0, stdout = "ok", stderr = "")
        val invoker = DriftDetectionService.chooseWikiInvoker(
            kind = BackendKind.CLAUDE_CLI,
            claude = {
                DefaultWikiAuthorInvoker(runner = runner, claudeCliPath = "/fake/claude", projectRoot = Paths.get("/tmp/proj"))
            },
            agentic = { fail("must not build agentic invoker for Claude selection"); error("unreachable") },
            codexUnsupported = { fail("must not build codex invoker for Claude selection"); error("unreachable") },
        )
        assertTrue("Claude route is the existing DefaultWikiAuthorInvoker", invoker is DefaultWikiAuthorInvoker)
        invoker.invoke(sampleEvents)
        val cmd = runner.lastCommand!!
        assertTrue("Claude path still passes --agents", "--agents" in cmd)
    }

    @Test fun `chooseWikiInvoker routes openai-compatible to AgenticWikiAuthorInvoker`() {
        val invoker = DriftDetectionService.chooseWikiInvoker(
            kind = BackendKind.OPENAI_COMPATIBLE_HTTP,
            claude = { fail("must not build Claude invoker for openai-compatible"); error("unreachable") },
            agentic = {
                AgenticWikiAuthorInvoker(
                    selection = AgentSelection("openai-compatible", "p1", "gw/model"),
                    wikiDir = null,
                    capability = ModelCapability.AGENTIC,
                    session = AgenticWikiSession { kotlin.Result.success(Unit) },
                )
            },
            codexUnsupported = { fail("must not build codex invoker for openai-compatible"); error("unreachable") },
        )
        assertTrue("openai-compatible route is the agentic invoker", invoker is AgenticWikiAuthorInvoker)
    }

    // ---- (b) agentic drives the digest through the tool loop ----

    @Test fun `agentic invoker drives digest into the session and acks all signatures`() = runBlocking {
        var receivedDigest: String? = null
        val session = AgenticWikiSession { digest ->
            receivedDigest = digest
            kotlin.Result.success(Unit)
        }
        val invoker = AgenticWikiAuthorInvoker(
            selection = AgentSelection("openai-compatible", "p1", "gw/model"),
            wikiDir = null,
            capability = ModelCapability.AGENTIC,
            session = session,
        )
        val result = invoker.invoke(sampleEvents)
        assertEquals(signatures, result.actedOnSignatures)
        assertTrue(result.skippedSignatures.isEmpty())
        assertNull(result.errorMessage)
        assertNotNull("session receives the digest", receivedDigest)
        assertTrue("digest is the wiki-author digest", receivedDigest!!.contains("@wiki-author"))
    }

    @Test fun `LoopBackedWikiSession drives the real agent loop and executes a tool call`() = runBlocking {
        val executed = mutableListOf<String>()
        val fakeExecutor = object : AgentToolExecutor {
            override fun execute(toolCall: AgentToolCall): ToolExecutionResult {
                executed.add(toolCall.name)
                return ToolExecutionResult(toolCall.id, "applied", isError = false)
            }
        }
        // Round 1: model asks to write a page via apply_patch. Round 2: model finishes with text.
        var round = 0
        val fakeClient = object : AgentClient {
            override suspend fun stream(request: AgentCompletionRequest): Flow<AgentStreamEvent> = flow {
                if (round++ == 0) {
                    emit(AgentStreamEvent.ToolFragment(index = 0, id = "t1", name = "apply_patch", arguments = "{}"))
                    emit(AgentStreamEvent.Finished("tool_calls"))
                } else {
                    emit(AgentStreamEvent.Text("done"))
                    emit(AgentStreamEvent.Finished("stop"))
                }
            }
        }
        val session = LoopBackedWikiSession(
            client = fakeClient,
            executor = fakeExecutor,
            tools = emptyList<OpenAiToolDefinition>(),
            modelId = "gw/model",
            systemPrompt = "You are the wiki author.",
            streaming = true,
        )
        val invoker = AgenticWikiAuthorInvoker(
            selection = AgentSelection("openai-compatible", "p1", "gw/model"),
            wikiDir = null,
            capability = ModelCapability.AGENTIC,
            session = session,
        )
        val result = invoker.invoke(sampleEvents)
        assertEquals("apply_patch executed by the loop", listOf("apply_patch"), executed)
        assertEquals(signatures, result.actedOnSignatures)
        assertNull(result.errorMessage)
    }

    // ---- (c) capability guard ----

    @Test fun `completion-only WIKI model is blocked without running`() = runBlocking {
        var ran = false
        val session = AgenticWikiSession { ran = true; kotlin.Result.success(Unit) }
        val invoker = AgenticWikiAuthorInvoker(
            selection = AgentSelection("openai-compatible", "p1", "gw/text-only"),
            wikiDir = null,
            capability = ModelCapability.COMPLETION_ONLY,
            session = session,
        )
        val result = invoker.invoke(sampleEvents)
        assertTrue("no run for a tool-less model", !ran)
        assertTrue(result.actedOnSignatures.isEmpty())
        assertEquals(signatures, result.skippedSignatures)
        val err = result.errorMessage!!
        assertTrue("error names the tool-capability problem", err.contains("not tool-capable"))
        assertTrue("error names the model id", err.contains("gw/text-only"))
    }

    @Test fun `codex WIKI selection returns a clear not-supported result`() = runBlocking {
        val invoker = DriftDetectionService.chooseWikiInvoker(
            kind = BackendKind.CODEX_APP_SERVER,
            claude = { fail("no"); error("unreachable") },
            agentic = { fail("no"); error("unreachable") },
            codexUnsupported = { CodexUnsupportedWikiAuthorInvoker },
        )
        val result = invoker.invoke(sampleEvents)
        assertTrue(result.actedOnSignatures.isEmpty())
        assertEquals(signatures, result.skippedSignatures)
        assertTrue(result.errorMessage!!.contains("Codex", ignoreCase = true))
    }

    @Test fun `agentic invoker with empty events is a no-op`() = runBlocking {
        var ran = false
        val invoker = AgenticWikiAuthorInvoker(
            selection = AgentSelection("openai-compatible", "p1", "gw/model"),
            wikiDir = null,
            capability = ModelCapability.AGENTIC,
            session = AgenticWikiSession { ran = true; kotlin.Result.success(Unit) },
        )
        val result = invoker.invoke(emptyList())
        assertTrue(!ran)
        assertTrue(result.actedOnSignatures.isEmpty())
        assertTrue(result.skippedSignatures.isEmpty())
        assertNull(result.errorMessage)
    }

    private class StubProcessRunner(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
    ) : DefaultWikiAuthorInvoker.ProcessRunner {
        var lastCommand: List<String>? = null
        override fun run(command: List<String>, projectRoot: java.nio.file.Path, timeoutSeconds: Long):
            DefaultWikiAuthorInvoker.ProcessResult {
            lastCommand = command
            return DefaultWikiAuthorInvoker.ProcessResult(exitCode, stdout, stderr, timedOut)
        }
    }
}
