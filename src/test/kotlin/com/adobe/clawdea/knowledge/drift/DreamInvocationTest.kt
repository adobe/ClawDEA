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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DreamInvocationTest {

    @Test fun `missing Dream capability returns unavailable and does not run prompt command`() {
        val runner = RecordingDreamProcessRunner(
            DreamProcessResult(exitCode = 0, stdout = "Claude Code help", stderr = "", timedOut = false),
        )
        val invocation = ClaudeDreamInvocation(
            timeoutSeconds = 5,
            runner = runner,
            cliPathProvider = { "claude-test" },
            environmentProvider = { mutableMapOf("PATH" to "/test/bin") },
        )

        val result = invocation.run(Files.createTempDirectory("dream-invocation"), "Return JSON only")

        assertEquals(DreamInvocationResult.Unavailable("Dreams unavailable"), result)
        assertEquals(1, runner.calls.size)
        assertEquals(listOf("claude-test", "--help"), runner.calls.single().command)
    }

    @Test fun `Dream capable invocation includes disallowed write edit and bash tools`() {
        val runner = RecordingDreamProcessRunner(
            DreamProcessResult(exitCode = 0, stdout = "Usage: /dream\nOptions: --disallowedTools", stderr = "", timedOut = false),
            DreamProcessResult(exitCode = 0, stdout = """{"candidates": []}""", stderr = "", timedOut = false),
        )
        val invocation = ClaudeDreamInvocation(
            timeoutSeconds = 5,
            runner = runner,
            cliPathProvider = { "claude-test" },
            environmentProvider = { mutableMapOf("PATH" to "/test/bin") },
        )

        val result = invocation.run(Files.createTempDirectory("dream-invocation"), "Return JSON only")

        assertEquals(DreamInvocationResult.Available("""{"candidates": []}"""), result)
        assertEquals(2, runner.calls.size)
        val promptCommand = runner.calls[1].command
        assertTrue(promptCommand.contains("--disallowedTools"))
        assertEquals("Write,Edit,MultiEdit,NotebookEdit,Bash", promptCommand[promptCommand.indexOf("--disallowedTools") + 1])
    }

    @Test fun `unsupported safe execution returns unavailable and does not run prompt command`() {
        val runner = RecordingDreamProcessRunner(
            DreamProcessResult(exitCode = 0, stdout = "Usage: /dream", stderr = "", timedOut = false),
        )
        val invocation = ClaudeDreamInvocation(
            timeoutSeconds = 5,
            runner = runner,
            cliPathProvider = { "claude-test" },
            environmentProvider = { mutableMapOf("PATH" to "/test/bin") },
        )

        val result = invocation.run(Files.createTempDirectory("dream-invocation"), "Return JSON only")

        assertEquals(DreamInvocationResult.Unavailable("Dreams unavailable"), result)
        assertEquals(1, runner.calls.size)
    }

    @Test fun `Dream capability probe is cached after first successful detection`() {
        val runner = RecordingDreamProcessRunner(
            DreamProcessResult(exitCode = 0, stdout = "Usage: /dream\nOptions: --disallowedTools", stderr = "", timedOut = false),
            DreamProcessResult(exitCode = 0, stdout = """{"candidates": []}""", stderr = "", timedOut = false),
            DreamProcessResult(exitCode = 0, stdout = """{"candidates": []}""", stderr = "", timedOut = false),
        )
        val invocation = ClaudeDreamInvocation(
            timeoutSeconds = 5,
            runner = runner,
            cliPathProvider = { "claude-test" },
            environmentProvider = { mutableMapOf("PATH" to "/test/bin") },
        )
        val root = Files.createTempDirectory("dream-invocation")

        invocation.run(root, "Return JSON only")
        invocation.run(root, "Return JSON only")

        assertEquals(3, runner.calls.size)
        assertEquals(1, runner.calls.count { it.command == listOf("claude-test", "--help") })
    }

    @Test fun `invocation passes merged environment to subprocess runner`() {
        val runner = RecordingDreamProcessRunner(
            DreamProcessResult(exitCode = 0, stdout = "dream command\n--disallowedTools", stderr = "", timedOut = false),
            DreamProcessResult(exitCode = 0, stdout = """{"candidates": []}""", stderr = "", timedOut = false),
        )
        val invocation = ClaudeDreamInvocation(
            timeoutSeconds = 5,
            runner = runner,
            cliPathProvider = { "claude-test" },
            environmentProvider = { mutableMapOf("PATH" to "/test/bin", "AUTH_SENTINEL" to "present") },
        )

        invocation.run(Files.createTempDirectory("dream-invocation"), "Return JSON only")

        assertEquals("present", runner.calls[1].environment["AUTH_SENTINEL"])
        assertEquals("/test/bin", runner.calls[1].environment["PATH"])
    }

    private class RecordingDreamProcessRunner(
        vararg results: DreamProcessResult,
    ) : DreamProcessRunner {
        private val results = ArrayDeque(results.toList())
        val calls = mutableListOf<DreamProcessCall>()

        override fun run(
            command: List<String>,
            projectRoot: java.nio.file.Path,
            environment: Map<String, String>,
            timeoutSeconds: Long,
        ): DreamProcessResult {
            calls += DreamProcessCall(command, projectRoot, environment)
            return results.removeFirst()
        }
    }

    private data class DreamProcessCall(
        val command: List<String>,
        val projectRoot: java.nio.file.Path,
        val environment: Map<String, String>,
    )
}
