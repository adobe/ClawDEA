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
package com.adobe.clawdea.provider.openai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostShellToolTest {

    @Test
    fun `denied approval returns denied result`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "confirm-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val runner = FakeProcessRunner(exitCode = 0, stdout = "ok", stderr = "")
        val tool = HostShellTool(
            project = null,
            approvalGate = gate,
            processRunner = runner,
            missingRouteBehavior = MissingRouteBehavior.DENY,
        )

        val result = tool.execute("echo hi", "tool-1")
        assertTrue(result.isError)
        assertTrue(result.content.contains("denied") || result.content.contains("not approved"))
        assertEquals(0, runner.executedCommands.size) // should not run
    }

    @Test
    fun `approved command runs and returns output`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "allow-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val runner = FakeProcessRunner(exitCode = 0, stdout = "hello", stderr = "")
        val tool = HostShellTool(
            project = null,
            approvalGate = gate,
            processRunner = runner,
            missingRouteBehavior = MissingRouteBehavior.DENY,
        )

        val result = tool.execute("echo hello", "tool-1")
        assertEquals(false, result.isError)
        assertTrue(result.content.contains("exit code: 0"))
        assertTrue(result.content.contains("hello"))
        assertEquals(1, runner.executedCommands.size)
        assertEquals("echo hello", runner.executedCommands[0])
    }

    @Test
    fun `non-zero exit code is captured`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "allow-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val runner = FakeProcessRunner(exitCode = 1, stdout = "error", stderr = "stderr output")
        val tool = HostShellTool(
            project = null,
            approvalGate = gate,
            processRunner = runner,
            missingRouteBehavior = MissingRouteBehavior.DENY,
        )

        val result = tool.execute("false", "tool-1")
        assertEquals(false, result.isError)
        assertTrue(result.content.contains("exit code: 1"))
        assertTrue(result.content.contains("error"))
        assertTrue(result.content.contains("stderr output"))
    }

    @Test
    fun `output exceeding 1 MiB is truncated`() {
        val gate = SharedToolApprovalGate(
            toolApprovalMode = { "allow-all" },
            policy = { null },
            route = { _, _, _ -> null },
            promptTimeoutMs = 1000,
        )
        val largeOutput = "x".repeat(2 * 1024 * 1024) // 2 MiB
        val runner = FakeProcessRunner(exitCode = 0, stdout = largeOutput, stderr = "")
        val tool = HostShellTool(
            project = null,
            approvalGate = gate,
            processRunner = runner,
            missingRouteBehavior = MissingRouteBehavior.DENY,
        )

        val result = tool.execute("cat large.txt", "tool-1")
        assertEquals(false, result.isError)
        assertTrue(result.content.contains("truncated"))
    }

    @Test
    fun `readBounded caps output at maxBytes and reports truncation`() {
        val largeContent = "a".repeat(2000)
        val reader = java.io.StringReader(largeContent)
        val (text, truncated) = HostShellTool.readBounded(reader, 100)
        assertEquals(100, text.length)
        assertEquals(true, truncated)
    }

    @Test
    fun `readBounded returns full content under cap`() {
        val smallContent = "hello"
        val reader = java.io.StringReader(smallContent)
        val (text, truncated) = HostShellTool.readBounded(reader, 100)
        assertEquals("hello", text)
        assertEquals(false, truncated)
    }

    /** Fake process runner for testing. */
    private class FakeProcessRunner(
        private val exitCode: Int,
        private val stdout: String,
        private val stderr: String,
    ) : HostShellTool.ProcessRunner {
        val executedCommands = mutableListOf<String>()

        override fun run(
            command: String,
            workingDir: String,
            env: Map<String, String>,
            timeoutMs: Long,
        ): HostShellTool.ProcessResult {
            executedCommands.add(command)
            val fullOutput = stdout + stderr
            val maxBytes = 1_048_576 // 1 MiB
            val truncated = fullOutput.length > maxBytes
            val output = if (truncated) fullOutput.substring(0, maxBytes) else fullOutput
            return HostShellTool.ProcessResult(
                exitCode = exitCode,
                output = output,
                timedOut = false,
                truncated = truncated,
            )
        }
    }
}
