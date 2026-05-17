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

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class WikiAuthorInvokerTest {

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

    @Test fun `strategy b — exit 0 dismisses every event`() = runBlocking {
        val runner = StubProcessRunner(exitCode = 0, stdout = "ok", stderr = "")
        val invoker = DefaultWikiAuthorInvoker(runner = runner, claudeCliPath = "/fake/claude", projectRoot = Paths.get("/tmp/proj"))
        val result = invoker.invoke(sampleEvents)
        assertEquals(sampleEvents.map { it.signature }.toSet(), result.actedOnSignatures)
        assertTrue(result.skippedSignatures.isEmpty())
        assertEquals(null, result.errorMessage)
    }

    @Test fun `strategy b — non-zero exit dismisses nothing and sets errorMessage`() = runBlocking {
        val runner = StubProcessRunner(exitCode = 1, stdout = "", stderr = "boom")
        val invoker = DefaultWikiAuthorInvoker(runner = runner, claudeCliPath = "/fake/claude", projectRoot = Paths.get("/tmp/proj"))
        val result = invoker.invoke(sampleEvents)
        assertTrue(result.actedOnSignatures.isEmpty())
        assertEquals(sampleEvents.map { it.signature }.toSet(), result.skippedSignatures)
        assertTrue(result.errorMessage!!.contains("exit code 1"))
    }

    @Test fun `command line includes --agents author-only-json and the digest`() = runBlocking {
        val runner = StubProcessRunner(exitCode = 0, stdout = "ok", stderr = "")
        val invoker = DefaultWikiAuthorInvoker(runner = runner, claudeCliPath = "/fake/claude", projectRoot = Paths.get("/tmp/proj"))
        invoker.invoke(sampleEvents)
        val cmd = runner.lastCommand!!
        assertTrue("contains --agents", "--agents" in cmd)
        val agentsIdx = cmd.indexOf("--agents")
        val agentsJson = cmd[agentsIdx + 1]
        assertTrue("agents JSON has wiki-author", "\"wiki-author\":" in agentsJson)
        assertTrue("agents JSON does NOT register wiki-librarian", "\"wiki-librarian\":" !in agentsJson)
        // Last arg is the prompt — starts with @wiki-author
        assertTrue("prompt starts with @wiki-author", cmd.last().startsWith("@wiki-author"))
    }

    @Test fun `timeout dismisses nothing and reports the timeout`() = runBlocking {
        val runner = StubProcessRunner(exitCode = -1, stdout = "", stderr = "", timedOut = true)
        val invoker = DefaultWikiAuthorInvoker(runner = runner, claudeCliPath = "/fake/claude", projectRoot = Paths.get("/tmp/proj"))
        val result = invoker.invoke(sampleEvents)
        assertTrue(result.actedOnSignatures.isEmpty())
        assertTrue(result.errorMessage!!.contains("timed out"))
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
