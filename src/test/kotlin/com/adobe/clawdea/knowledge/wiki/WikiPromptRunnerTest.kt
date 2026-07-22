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
package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.knowledge.drift.AgenticWikiSession
import com.adobe.clawdea.knowledge.drift.DefaultWikiAuthorInvoker
import com.adobe.clawdea.provider.openai.catalog.ModelCapability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class WikiPromptRunnerTest {

    private val root = Path.of(".")

    private fun runnerReturning(result: DefaultWikiAuthorInvoker.ProcessResult) =
        object : DefaultWikiAuthorInvoker.ProcessRunner {
            var lastCommand: List<String> = emptyList()
            override fun run(command: List<String>, projectRoot: Path, timeoutSeconds: Long): DefaultWikiAuthorInvoker.ProcessResult {
                lastCommand = command
                return result
            }
        }

    @Test fun `claude runner passes the prompt after -- and includes the model, without --agents`() = runBlocking {
        val fake = runnerReturning(DefaultWikiAuthorInvoker.ProcessResult(0, """{"type":"result"}""", "", false))
        val runner = ClaudeWikiPromptRunner(fake, claudeCliPath = "claude", projectRoot = root, mcpPort = 0, modelId = "claude-opus-4-8")
        val res = runner.run("BOOTSTRAP PROMPT")
        assertTrue(res.ok)
        assertTrue("prompt must be the final argv token after --", fake.lastCommand.last() == "BOOTSTRAP PROMPT")
        assertTrue(fake.lastCommand.contains("--model"))
        assertTrue(fake.lastCommand.contains("claude-opus-4-8"))
        assertFalse("seed must not inject --agents", fake.lastCommand.contains("--agents"))
    }

    @Test fun `claude runner non-zero exit reports error`() = runBlocking {
        val fake = runnerReturning(DefaultWikiAuthorInvoker.ProcessResult(2, "", "boom", false))
        val runner = ClaudeWikiPromptRunner(fake, "claude", root, 0, "m")
        val res = runner.run("p")
        assertFalse(res.ok)
        assertTrue(res.errorMessage!!.contains("boom"))
    }

    @Test fun `claude runner timeout reports error`() = runBlocking {
        val fake = runnerReturning(DefaultWikiAuthorInvoker.ProcessResult(-1, "", "", true))
        val runner = ClaudeWikiPromptRunner(fake, "claude", root, 0, "m", timeoutSeconds = 5)
        val res = runner.run("p")
        assertFalse(res.ok)
        assertTrue(res.errorMessage!!.contains("timed out"))
    }

    @Test fun `agentic runner refuses a non-agentic model`() = runBlocking {
        val session = AgenticWikiSession { Result.success(Unit) }
        val runner = AgenticWikiPromptRunner(session, ModelCapability.COMPLETION_ONLY, modelLabel = "gpt-x")
        val res = runner.run("p")
        assertFalse(res.ok)
        assertTrue(res.errorMessage!!.contains("not tool-capable") || res.errorMessage!!.contains("agentic"))
    }

    @Test fun `agentic runner success on agentic model`() = runBlocking {
        val session = AgenticWikiSession { Result.success(Unit) }
        val runner = AgenticWikiPromptRunner(session, ModelCapability.AGENTIC, modelLabel = "gpt-x")
        val res = runner.run("p")
        assertTrue(res.ok)
    }

    @Test fun `codex runner is unsupported`() = runBlocking {
        val res = CodexUnsupportedWikiPromptRunner.run("p")
        assertFalse(res.ok)
        assertTrue(res.errorMessage!!.contains("Codex"))
    }
}
