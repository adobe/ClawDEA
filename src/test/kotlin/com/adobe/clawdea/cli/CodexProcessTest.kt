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
package com.adobe.clawdea.cli

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexProcessTest {

    // --- Pure command / prompt helpers ---

    @Test
    fun `first turn command has required flags and no resume`() {
        val cmd = CodexProcess.buildCommand(
            cliPath = "codex", model = "gpt-5-codex", effort = "high", mcpPort = 4567,
            workingDirectory = "/work", prompt = "hello", resumeThreadId = null,
        )
        assertEquals("codex", cmd[0])
        assertEquals("exec", cmd[1])
        assertTrue(cmd.contains("--json"))
        assertTrue(consecutive(cmd, "-s", "danger-full-access"))
        assertTrue(consecutive(cmd, "-c", "approval_policy=\"never\""))
        assertTrue(consecutive(cmd, "-c", """mcp_servers.clawdea.url="http://127.0.0.1:4567/mcp""""))
        assertTrue(consecutive(cmd, "-m", "gpt-5-codex"))
        assertTrue(consecutive(cmd, "-c", "model_reasoning_effort=\"high\""))
        assertTrue(consecutive(cmd, "-C", "/work"))
        assertFalse(cmd.contains("resume"))
        assertEquals("hello", cmd.last())
    }

    @Test
    fun `resume turn appends resume subcommand and thread id before the prompt`() {
        val cmd = CodexProcess.buildCommand(
            cliPath = "codex", model = "default", effort = "default", mcpPort = 0,
            workingDirectory = "/work", prompt = "again", resumeThreadId = "thread-xyz",
        )
        // exec-level flags precede the resume subcommand.
        val resumeIdx = cmd.indexOf("resume")
        assertTrue(resumeIdx > cmd.indexOf("--json"))
        assertEquals("thread-xyz", cmd[resumeIdx + 1])
        assertEquals("again", cmd.last())
        // "default" model/effort are omitted.
        assertFalse(cmd.contains("-m"))
        assertFalse(cmd.any { it.startsWith("model_reasoning_effort") })
    }

    @Test
    fun `forceChatGptAuth pins codex to the ChatGPT credential and default omits it`() {
        val pinned = CodexProcess.buildCommand(
            cliPath = "codex", model = "gpt-5.6-sol", effort = "default", mcpPort = 0,
            workingDirectory = "/work", prompt = "hi", resumeThreadId = null, forceChatGptAuth = true,
        )
        assertTrue(consecutive(pinned, "-c", "preferred_auth_method=\"chatgpt\""))

        val unpinned = CodexProcess.buildCommand(
            cliPath = "codex", model = "gpt-5-codex", effort = "default", mcpPort = 0,
            workingDirectory = "/work", prompt = "hi", resumeThreadId = null, forceChatGptAuth = false,
        )
        assertFalse(unpinned.any { it.startsWith("preferred_auth_method") })
    }

    @Test
    fun `effort maps to codex enum and collapses xhigh and max to high`() {
        assertEquals("minimal", CodexProcess.mapEffort("minimal"))
        assertEquals("low", CodexProcess.mapEffort("low"))
        assertEquals("high", CodexProcess.mapEffort("xhigh"))
        assertEquals("high", CodexProcess.mapEffort("max"))
        assertNull(CodexProcess.mapEffort("default"))
        assertNull(CodexProcess.mapEffort(""))
    }

    @Test
    fun `extractUserText reads content from the Claude-format user message`() {
        val json = """{"type":"user","message":{"role":"user","content":"do the thing"}}"""
        assertEquals("do the thing", CodexProcess.extractUserText(json))
    }

    @Test
    fun `extractUserText returns null for malformed input`() {
        assertNull(CodexProcess.extractUserText("garbage"))
    }

    // --- Persistent-facade round trip ---

    @Test
    fun `writeLine spawns a turn whose stdout lines are drained by readLine`() {
        val commands = CopyOnWriteArrayList<List<String>>()
        val proc = fakeCodex(
            commands,
            listOf(
                """{"type":"thread.started","thread_id":"tid-1"}""",
                """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"ok"}}""",
                """{"type":"turn.completed","usage":{"input_tokens":1,"cached_input_tokens":0,"output_tokens":1,"reasoning_output_tokens":0}}""",
            ),
        )
        proc.start(resumeSessionId = null)
        proc.writeLine("""{"type":"user","message":{"content":"hi"}}""")

        assertTrue(readLine(proc).contains("thread.started"))
        assertTrue(readLine(proc).contains("agent_message"))
        assertTrue(readLine(proc).contains("turn.completed"))

        // First turn had no resume.
        assertFalse(commands[0].contains("resume"))
    }

    @Test
    fun `second turn resumes the sniffed thread id`() {
        val commands = CopyOnWriteArrayList<List<String>>()
        val proc = fakeCodex(
            commands,
            listOf("""{"type":"thread.started","thread_id":"tid-42"}"""),
        )
        proc.start(resumeSessionId = null)
        proc.writeLine("""{"type":"user","message":{"content":"first"}}""")
        // Draining the line guarantees the stdout pump processed (and sniffed) it.
        assertTrue(readLine(proc).contains("tid-42"))

        proc.writeLine("""{"type":"user","message":{"content":"second"}}""")
        // The second spawn must resume the sniffed thread id.
        assertEventually { commands.size == 2 }
        val second = commands[1]
        val resumeIdx = second.indexOf("resume")
        assertTrue(resumeIdx > 0)
        assertEquals("tid-42", second[resumeIdx + 1])
    }

    @Test
    fun `first turn prepends the instructions preamble, resume turn does not`() {
        val commands = CopyOnWriteArrayList<List<String>>()
        val proc = fakeCodex(
            commands,
            listOf("""{"type":"thread.started","thread_id":"tid-9"}"""),
            instructions = { _, _ -> "PREAMBLE-MARKER" },
        )
        proc.start(resumeSessionId = null)
        proc.writeLine("""{"type":"user","message":{"content":"first"}}""")
        assertTrue(readLine(proc).contains("tid-9"))

        // First turn: prompt arg carries the preamble AND the user request.
        val firstPrompt = commands[0].last()
        assertTrue(firstPrompt.contains("PREAMBLE-MARKER"))
        assertTrue(firstPrompt.contains("first"))

        proc.writeLine("""{"type":"user","message":{"content":"second"}}""")
        assertEventually { commands.size == 2 }
        // Resume turn: no preamble, just the user request.
        assertEquals("second", commands[1].last())
        assertFalse(commands[1].last().contains("PREAMBLE-MARKER"))
    }

    @Test
    fun `start with a resume session id skips the first-turn preamble`() {
        val commands = CopyOnWriteArrayList<List<String>>()
        val proc = fakeCodex(
            commands,
            listOf("""{"type":"thread.started","thread_id":"tid-r"}"""),
            instructions = { _, _ -> "PREAMBLE-MARKER" },
        )
        // Resuming an existing thread — instructions already persisted, don't resend.
        proc.start(resumeSessionId = "existing-thread")
        proc.writeLine("""{"type":"user","message":{"content":"go"}}""")
        assertEventually { commands.size == 1 }
        assertFalse(commands[0].last().contains("PREAMBLE-MARKER"))
        assertEquals("go", commands[0].last())
    }

    @Test
    fun `isResumeFailure matches codex no-rollout errors only`() {
        assertTrue(CodexProcess.isResumeFailure("Error: thread/resume: thread/resume failed: no rollout found for thread id 4d71a0c4 (code -32600)"))
        assertTrue(CodexProcess.isResumeFailure("no rollout found for thread id abc"))
        assertFalse(CodexProcess.isResumeFailure("some unrelated stderr noise"))
        assertFalse(CodexProcess.isResumeFailure("Reading additional input from stdin..."))
    }

    @Test
    fun `a resume that finds no thread retries the prompt on a fresh session`() {
        val commands = CopyOnWriteArrayList<List<String>>()
        // First spawn (resume) dies with codex's no-rollout error on stderr and no stdout events;
        // second spawn (fresh) answers normally.
        val proc = CodexProcess(
            workingDirectory = "/work",
            mcpPort = 0,
            cliPathProvider = { "codex" },
            modelProvider = { "gpt-5.6-sol" },
            effortProvider = { "default" },
            forceChatGptAuthProvider = { false },
            envProvider = { emptyMap() },
            spawner = { command, _, _ ->
                commands.add(command)
                if (command.contains("resume")) {
                    FakeProcess("", "Error: thread/resume: thread/resume failed: no rollout found for thread id x (code -32600)")
                } else {
                    FakeProcess(
                        listOf(
                            """{"type":"thread.started","thread_id":"019f-new"}""",
                            """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"recovered"}}""",
                        ).joinToString("\n"),
                    )
                }
            },
            instructionsProvider = { _, _ -> "" },
        )
        proc.start(resumeSessionId = "4d71a0c4-8b8b-49f8-b9ce-35c66e6564b5")
        proc.writeLine("""{"type":"user","message":{"content":"hello"}}""")

        // The fresh retry's stdout is drained by readLine.
        assertTrue(readLine(proc).contains("thread.started"))
        assertTrue(readLine(proc).contains("recovered"))

        assertEventually { commands.size == 2 }
        assertTrue("first spawn resumes", commands[0].contains("resume"))
        assertFalse("retry is fresh (no resume)", commands[1].contains("resume"))
    }

    @Test
    fun `stop unblocks readLine with a null end-of-stream`() {
        val proc = fakeCodex(CopyOnWriteArrayList(), emptyList())
        proc.start(resumeSessionId = null)
        assertTrue(proc.isAlive)
        proc.stop()
        assertFalse(proc.isAlive)
        assertNull(proc.readLine())
    }

    @Test
    fun `restart after stop does not leak a stale STOP into the new session's reader`() {
        // Regression: resuming a session does bridge.stop() then bridge.start() on the same
        // CodexProcess. stop() enqueues the STOP sentinel to unblock the old reader; a shared queue
        // would let it reach the new reader, whose readLine() would return null and make CliBridge
        // report a bogus "CLI process exited unexpectedly" before any turn ran. A fresh per-session
        // queue must isolate the two so the new session streams its turn normally.
        val commands = CopyOnWriteArrayList<List<String>>()
        val proc = fakeCodex(
            commands,
            listOf(
                """{"type":"thread.started","thread_id":"tid-2"}""",
                """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"back"}}""",
            ),
        )
        proc.start(resumeSessionId = null)
        proc.stop() // enqueues STOP into the first session's queue
        assertNull(proc.readLine()) // old reader drains its own STOP

        proc.start(resumeSessionId = null) // fresh queue
        proc.writeLine("""{"type":"user","message":{"content":"hi again"}}""")
        // Must be the turn's events, never a leaked null/STOP.
        assertTrue(readLine(proc).contains("thread.started"))
        assertTrue(readLine(proc).contains("agent_message"))
    }

    // --- helpers ---

    private fun readLine(p: CodexProcess): String =
        p.readLine() ?: error("expected a line, got end-of-stream")

    private fun consecutive(list: List<String>, a: String, b: String): Boolean =
        (0 until list.size - 1).any { list[it] == a && list[it + 1] == b }

    private fun assertEventually(timeoutMs: Long = 2000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        assertTrue("condition not met within ${timeoutMs}ms", cond())
    }

    private fun fakeCodex(
        commands: MutableList<List<String>>,
        lines: List<String>,
        instructions: (com.intellij.openapi.project.Project?, List<com.adobe.clawdea.skills.SkillInfo>) -> String = { _, _ -> "" },
    ): CodexProcess =
        CodexProcess(
            workingDirectory = "/work",
            mcpPort = 1234,
            cliPathProvider = { "codex" },
            modelProvider = { "default" },
            effortProvider = { "default" },
            forceChatGptAuthProvider = { false },
            envProvider = { emptyMap() },
            spawner = { command, _, _ ->
                commands.add(command)
                FakeProcess(lines.joinToString("\n"))
            },
            instructionsProvider = instructions,
        )

    private class FakeProcess(stdout: String, stderr: String = "") : Process() {
        private val stdoutStream = ByteArrayInputStream(stdout.toByteArray())
        private val stderrStream = ByteArrayInputStream(stderr.toByteArray())
        private val sink = object : OutputStream() { override fun write(b: Int) {} }
        @Volatile private var done = false
        override fun getOutputStream(): OutputStream = sink
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream
        override fun waitFor(): Int { done = true; return 0 }
        override fun exitValue(): Int = if (done) 0 else throw IllegalThreadStateException()
        override fun destroy() { done = true }
        override fun isAlive(): Boolean = !done
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { done = true; return true }
    }
}
