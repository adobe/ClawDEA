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

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets

/**
 * Drives [CodexAppServerProcess] against an in-memory fake `codex app-server` (piped stdio) to
 * verify the JSON-RPC handshake, turn dispatch, auto-approval, notification forwarding, and shutdown.
 */
class CodexAppServerProcessTest {

    /** A [Process] whose stdio is backed by pipes the test can drive both ends of. */
    private class FakeProcess(
        private val stdin: PipedOutputStream, // what the subprocess reads (parent writes here)
        private val stdout: PipedInputStream,  // what the subprocess emits (parent reads here)
    ) : Process() {
        @Volatile var killed = false
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (killed) 137 else 0
        override fun destroy() { killed = true }
        override fun destroyForcibly(): Process { killed = true; return this }
        override fun isAlive(): Boolean = !killed
    }

    private class Harness(approvalGate: CodexApprovalGate? = null) {
        // Subprocess stdin: parent (process under test) writes; test reads via [fromProcess].
        private val processStdinSink = PipedOutputStream()
        private val fromProcess = BufferedReader(
            InputStreamReader(PipedInputStream(processStdinSink), StandardCharsets.UTF_8),
        )
        // Subprocess stdout: test writes via [toProcess]; parent reads.
        private val processStdoutSource = PipedInputStream()
        val toProcess = PipedOutputStream(processStdoutSource)

        val fake = FakeProcess(processStdinSink, processStdoutSource)

        val process = CodexAppServerProcess(
            workingDirectory = "/tmp",
            mcpPort = 0,
            project = null,
            cliPathProvider = { "codex" },
            modelProvider = { "gpt-5.6-sol" },
            effortProvider = { "medium" },
            forceChatGptAuthProvider = { false },
            envProvider = { emptyMap() },
            spawner = { _, _, _ -> fake },
            instructionsProvider = { _, _ -> "BASE INSTRUCTIONS" },
            approvalGate = approvalGate,
        )

        /** Reads the next JSON object the process wrote to its stdin. */
        fun readOutbound(): com.google.gson.JsonObject {
            val line = fromProcess.readLine() ?: error("process wrote nothing")
            return JsonParser.parseString(line).asJsonObject
        }

        fun feed(json: String) {
            toProcess.write((json + "\n").toByteArray(StandardCharsets.UTF_8))
            toProcess.flush()
        }
    }

    /** A gate whose interactive route always resolves to [decision] (no UI). */
    private fun gateReturning(decision: com.adobe.clawdea.chat.permission.PermissionRequest.Decision): CodexApprovalGate {
        val dispatcher = object : com.adobe.clawdea.chat.permission.PermissionDispatcher(onRender = {}) {
            override fun submit(toolName: String, inputJson: String, timeoutMs: Long, toolUseId: String?) =
                Result(decision)
        }
        return CodexApprovalGate(
            toolApprovalMode = { "confirm-all" },
            autoAcceptEdits = { false },
            policy = { null },
            route = { _, _, toolUseId ->
                com.adobe.clawdea.chat.permission.PermissionRouterRegistry.Routed(dispatcher, toolUseId)
            },
        )
    }

    /** Runs the full initialize → thread/start → thread ready handshake, returning the harness. */
    private fun handshake(h: Harness) {
        val init = h.readOutbound()
        assertEquals("initialize", init.get("method").asString)
        val initId = init.get("id").asLong
        h.feed("""{"jsonrpc":"2.0","id":$initId,"result":{}}""")

        val inited = h.readOutbound()
        assertEquals("initialized", inited.get("method").asString)

        val threadStart = h.readOutbound()
        assertEquals("thread/start", threadStart.get("method").asString)
        val params = threadStart.getAsJsonObject("params")
        assertEquals("/tmp", params.get("cwd").asString)
        assertEquals("on-request", params.get("approvalPolicy").asString)
        assertEquals("workspace-write", params.get("sandbox").asString)
        assertEquals("BASE INSTRUCTIONS", params.get("baseInstructions").asString)
        val threadId = threadStart.get("id").asLong
        h.feed("""{"jsonrpc":"2.0","id":$threadId,"result":{"thread":{"id":"THREAD-1"}}}""")
    }

    @Test(timeout = 10_000)
    fun `handshake issues initialize, initialized, then thread start`() {
        val h = Harness()
        h.process.start(resumeSessionId = null, skills = emptyList())
        handshake(h)
        h.process.stop()
    }

    @Test(timeout = 10_000)
    fun `thread start requests a detailed reasoning summary so the Thinking block streams`() {
        val h = Harness()
        h.process.start(resumeSessionId = null, skills = emptyList())

        val init = h.readOutbound()
        h.feed("""{"jsonrpc":"2.0","id":${init.get("id").asLong},"result":{}}""")
        h.readOutbound() // initialized

        val threadStart = h.readOutbound()
        assertEquals("thread/start", threadStart.get("method").asString)
        val config = threadStart.getAsJsonObject("params").getAsJsonObject("config")
        // "auto"/"none" yield no summary deltas over the app-server — the level must be concrete.
        assertEquals("detailed", config.get("model_reasoning_summary").asString)
        h.process.stop()
    }

    @Test(timeout = 10_000)
    fun `thread resume also requests the reasoning summary`() {
        val h = Harness()
        h.process.start(resumeSessionId = "OLD-THREAD", skills = emptyList())

        val init = h.readOutbound()
        h.feed("""{"jsonrpc":"2.0","id":${init.get("id").asLong},"result":{}}""")
        h.readOutbound() // initialized

        val resume = h.readOutbound()
        assertEquals("thread/resume", resume.get("method").asString)
        val config = resume.getAsJsonObject("params").getAsJsonObject("config")
        assertEquals("detailed", config.get("model_reasoning_summary").asString)
        h.process.stop()
    }

    @Test(timeout = 10_000)
    fun `writeLine after thread ready issues a turn start with the user prompt`() {
        val h = Harness()
        h.process.start(resumeSessionId = null, skills = emptyList())
        handshake(h)

        h.process.writeLine("""{"type":"user","message":{"role":"user","content":"what model are you?"}}""")
        val turn = h.readOutbound()
        assertEquals("turn/start", turn.get("method").asString)
        val params = turn.getAsJsonObject("params")
        assertEquals("THREAD-1", params.get("threadId").asString)
        assertEquals("gpt-5.6-sol", params.get("model").asString)
        assertEquals("medium", params.get("effort").asString)
        val input = params.getAsJsonArray("input")
        assertEquals("what model are you?", input[0].asJsonObject.get("text").asString)
        h.process.stop()
    }

    @Test(timeout = 10_000)
    fun `steer injects into an active turn via turn steer`() {
        val h = Harness()
        h.process.start(resumeSessionId = null, skills = emptyList())
        handshake(h)

        // No steerable turn yet → steer declines without writing anything.
        assertFalse(h.process.steer("go left"))

        // Start a turn and acknowledge it so the steer window opens.
        h.process.writeLine("""{"type":"user","message":{"role":"user","content":"start"}}""")
        val turn = h.readOutbound()
        assertEquals("turn/start", turn.get("method").asString)
        h.feed("""{"jsonrpc":"2.0","id":${turn.get("id").asLong},"result":{"turn":{"id":"TURN-1"}}}""")
        // The reader pumps pipe lines FIFO, so draining a following notification guarantees the
        // turn/start response above was already applied (turnActive == true) before we steer.
        h.feed("""{"jsonrpc":"2.0","method":"item/agentMessage/delta","params":{"delta":"..."}}""")
        h.process.readLine()

        assertTrue(h.process.steer("actually, use TypeScript"))
        val steer = h.readOutbound()
        assertEquals("turn/steer", steer.get("method").asString)
        val params = steer.getAsJsonObject("params")
        assertEquals("THREAD-1", params.get("threadId").asString)
        assertEquals("actually, use TypeScript", params.getAsJsonArray("input")[0].asJsonObject.get("text").asString)
        h.process.stop()
    }

    @Test(timeout = 10_000)
    fun `steer declines once the turn completes`() {
        val h = Harness()
        h.process.start(resumeSessionId = null, skills = emptyList())
        handshake(h)

        h.process.writeLine("""{"type":"user","message":{"role":"user","content":"start"}}""")
        val turn = h.readOutbound()
        h.feed("""{"jsonrpc":"2.0","id":${turn.get("id").asLong},"result":{"turn":{"id":"TURN-1"}}}""")

        // Terminal turn notification closes the steer window.
        h.feed("""{"jsonrpc":"2.0","method":"turn/completed","params":{"turn":{"id":"TURN-1"}}}""")
        h.process.readLine() // drain the forwarded turn/completed notification

        assertFalse(h.process.steer("too late"))
        h.process.stop()
    }

    @Test
    fun `codex backend advertises steer support`() {
        val h = Harness()
        assertTrue(h.process.supportsSteer)
    }

    @Test(timeout = 10_000)
    fun `a prompt sent before the thread is ready is deferred then flushed`() {
        val h = Harness()
        h.process.start(resumeSessionId = null, skills = emptyList())
        // Send the prompt before completing the handshake.
        h.process.writeLine("""{"type":"user","message":{"role":"user","content":"early"}}""")
        handshake(h)
        // The deferred prompt is sent as soon as the thread id lands.
        val turn = h.readOutbound()
        assertEquals("turn/start", turn.get("method").asString)
        assertEquals("early", turn.getAsJsonObject("params").getAsJsonArray("input")[0].asJsonObject.get("text").asString)
        h.process.stop()
    }

    @Test(timeout = 10_000)
    fun `a server approval request is auto-approved`() {
        val h = Harness()
        h.process.start(resumeSessionId = null, skills = emptyList())
        handshake(h)

        h.feed("""{"jsonrpc":"2.0","id":777,"method":"item/commandExecution/requestApproval","params":{}}""")
        val reply = h.readOutbound()
        assertEquals(777, reply.get("id").asLong)
        assertEquals("accept", reply.getAsJsonObject("result").get("decision").asString)
        h.process.stop()
    }

    @Test(timeout = 10_000)
    fun `command approval is routed through the gate and can be denied`() {
        val h = Harness(approvalGate = gateReturning(com.adobe.clawdea.chat.permission.PermissionRequest.Decision.DENY))
        h.process.start(resumeSessionId = null, skills = emptyList())
        handshake(h)

        h.feed(
            """{"jsonrpc":"2.0","id":900,"method":"item/commandExecution/requestApproval",""" +
                """"params":{"itemId":"c1","command":"rm -rf build"}}""",
        )
        val reply = h.readOutbound()
        assertEquals(900, reply.get("id").asLong)
        assertEquals("deny", reply.getAsJsonObject("result").get("decision").asString)
        h.process.stop()
    }

    @Test(timeout = 10_000)
    fun `command approval is routed through the gate and can be accepted`() {
        val h = Harness(approvalGate = gateReturning(com.adobe.clawdea.chat.permission.PermissionRequest.Decision.ALLOW))
        h.process.start(resumeSessionId = null, skills = emptyList())
        handshake(h)

        h.feed(
            """{"jsonrpc":"2.0","id":901,"method":"item/commandExecution/requestApproval",""" +
                """"params":{"itemId":"c1","command":"ls"}}""",
        )
        val reply = h.readOutbound()
        assertEquals(901, reply.get("id").asLong)
        assertEquals("accept", reply.getAsJsonObject("result").get("decision").asString)
        h.process.stop()
    }

    /**
     * Hands out a *fresh* piped [FakeProcess] on every spawn so a stop()->start() restart of the
     * same [CodexAppServerProcess] instance can be driven end-to-end (the fixed-pipe [Harness]
     * can't be reused across restarts). Used to guard the approval-executor lifecycle.
     */
    private class RestartHarness(approvalGate: CodexApprovalGate?) {
        @Volatile private var fromProcess: BufferedReader? = null
        @Volatile private var toProcess: PipedOutputStream? = null

        val process = CodexAppServerProcess(
            workingDirectory = "/tmp",
            mcpPort = 0,
            project = null,
            cliPathProvider = { "codex" },
            modelProvider = { "gpt-5.6-sol" },
            effortProvider = { "medium" },
            forceChatGptAuthProvider = { false },
            envProvider = { emptyMap() },
            spawner = { _, _, _ ->
                val stdinSink = PipedOutputStream()
                fromProcess = BufferedReader(InputStreamReader(PipedInputStream(stdinSink), StandardCharsets.UTF_8))
                val stdoutSource = PipedInputStream()
                toProcess = PipedOutputStream(stdoutSource)
                FakeProcess(stdinSink, stdoutSource)
            },
            instructionsProvider = { _, _ -> "BASE INSTRUCTIONS" },
            approvalGate = approvalGate,
        )

        fun readOutbound(): com.google.gson.JsonObject {
            val line = fromProcess!!.readLine() ?: error("process wrote nothing")
            return JsonParser.parseString(line).asJsonObject
        }

        fun feed(json: String) {
            val out = toProcess!!
            out.write((json + "\n").toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }

        fun handshake() {
            val init = readOutbound()
            assertEquals("initialize", init.get("method").asString)
            feed("""{"jsonrpc":"2.0","id":${init.get("id").asLong},"result":{}}""")
            readOutbound() // initialized
            val threadStart = readOutbound()
            assertEquals("thread/start", threadStart.get("method").asString)
            feed("""{"jsonrpc":"2.0","id":${threadStart.get("id").asLong},"result":{"thread":{"id":"THREAD-1"}}}""")
        }
    }

    @Test(timeout = 10_000)
    fun `command approval is still answered after a stop-start restart`() {
        val h = RestartHarness(gateReturning(com.adobe.clawdea.chat.permission.PermissionRequest.Decision.ALLOW))

        h.process.start(resumeSessionId = null, skills = emptyList())
        h.handshake()
        h.feed("""{"jsonrpc":"2.0","id":500,"method":"item/commandExecution/requestApproval","params":{"command":"ls"}}""")
        assertEquals("accept", h.readOutbound().getAsJsonObject("result").get("decision").asString)

        // Restart the SAME process: stop() shuts the approval executor down.
        h.process.stop()
        h.process.start(resumeSessionId = null, skills = emptyList())
        h.handshake()

        // Before the executor-revival fix this approval was submitted to a terminated executor,
        // dropped with RejectedExecutionException, and never answered — so readOutbound() below
        // would block until the test timed out. It must now be accepted like the first session's.
        h.feed("""{"jsonrpc":"2.0","id":501,"method":"item/commandExecution/requestApproval","params":{"command":"ls"}}""")
        assertEquals("accept", h.readOutbound().getAsJsonObject("result").get("decision").asString)

        h.process.stop()
    }

    @Test(timeout = 10_000)
    fun `an elicitation request is auto-accepted`() {
        val h = Harness()
        h.process.start(resumeSessionId = null, skills = emptyList())
        handshake(h)

        h.feed("""{"jsonrpc":"2.0","id":42,"method":"mcpServer/elicitation/request","params":{}}""")
        val reply = h.readOutbound()
        assertEquals(42, reply.get("id").asLong)
        assertEquals("accept", reply.getAsJsonObject("result").get("action").asString)
        h.process.stop()
    }

    @Test(timeout = 10_000)
    fun `notifications are forwarded to readLine`() {
        val h = Harness()
        h.process.start(resumeSessionId = null, skills = emptyList())
        handshake(h)

        val notif = """{"jsonrpc":"2.0","method":"item/agentMessage/delta","params":{"delta":"hi"}}"""
        h.feed(notif)
        val out = h.process.readLine()
        assertTrue(out!!.contains("item/agentMessage/delta"))
        h.process.stop()
    }

    @Test(timeout = 10_000)
    fun `resume issues thread resume and falls back to a fresh start on failure`() {
        val h = Harness()
        h.process.start(resumeSessionId = "OLD-THREAD", skills = emptyList())

        val init = h.readOutbound()
        h.feed("""{"jsonrpc":"2.0","id":${init.get("id").asLong},"result":{}}""")
        h.readOutbound() // initialized

        val resume = h.readOutbound()
        assertEquals("thread/resume", resume.get("method").asString)
        assertEquals("OLD-THREAD", resume.getAsJsonObject("params").get("threadId").asString)
        // Reject the resume: process should fall back to a fresh thread/start.
        h.feed("""{"jsonrpc":"2.0","id":${resume.get("id").asLong},"error":{"message":"no rollout found"}}""")

        val fresh = h.readOutbound()
        assertEquals("thread/start", fresh.get("method").asString)
        h.process.stop()
    }

    @Test(timeout = 10_000)
    fun `stop makes readLine return null`() {
        val h = Harness()
        h.process.start(resumeSessionId = null, skills = emptyList())
        handshake(h)
        h.process.stop()
        assertNull(h.process.readLine())
    }

    @Test
    fun `extractUserText pulls content from the Claude-format envelope`() {
        val text = CodexAppServerProcess.extractUserText(
            """{"type":"user","message":{"role":"user","content":"hello"}}""",
        )
        assertEquals("hello", text)
    }

    @Test
    fun `mapEffort collapses xhigh to high and passes known values`() {
        assertEquals("minimal", CodexAppServerProcess.mapEffort("minimal"))
        assertEquals("low", CodexAppServerProcess.mapEffort("low"))
        assertEquals("medium", CodexAppServerProcess.mapEffort("medium"))
        assertEquals("high", CodexAppServerProcess.mapEffort("high"))
        assertEquals("high", CodexAppServerProcess.mapEffort("xhigh"))
        assertNull(CodexAppServerProcess.mapEffort("default"))
        assertNull(CodexAppServerProcess.mapEffort(""))
    }
}
