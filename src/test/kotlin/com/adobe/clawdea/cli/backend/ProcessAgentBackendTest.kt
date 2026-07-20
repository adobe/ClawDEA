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

import com.adobe.clawdea.cli.AgentEventParser
import com.adobe.clawdea.cli.AgentProcess
import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.cli.CliEventParser
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.skills.SkillInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class ProcessAgentBackendTest {

    @Test
    fun `adapter serializes user text and parses process output`() {
        val process = FakeAgentProcess(
            output = listOf("""{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"hi"}}}"""),
        )
        val backend = ProcessAgentBackend(
            process,
            CliEventParser(),
            SteeringMode.NONE,
            BackendKind.CLAUDE_CLI,
            "Claude"
        )
        backend.start(null, emptyList())
        backend.sendMessage("hello\nworld")

        assertTrue(process.lastWrite!!.contains("hello\\nworld"))
        assertEquals(CliEvent.TextDelta("hi"), backend.readEvent())
    }

    @Test
    fun `readEvent skips blank lines`() {
        val process = FakeAgentProcess(
            output = listOf("", "  ", """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"content"}}}""")
        )
        val backend = ProcessAgentBackend(
            process,
            CliEventParser(),
            SteeringMode.NONE,
            BackendKind.CLAUDE_CLI,
            "Claude"
        )
        backend.start(null, emptyList())

        // Should skip blanks and return the first non-blank parsed event
        assertEquals(CliEvent.TextDelta("content"), backend.readEvent())
    }

    @Test
    fun `readEvent returns null on EOF`() {
        val process = FakeAgentProcess(output = emptyList())
        val backend = ProcessAgentBackend(
            process,
            CliEventParser(),
            SteeringMode.NONE,
            BackendKind.CLAUDE_CLI,
            "Claude"
        )
        backend.start(null, emptyList())

        assertNull(backend.readEvent())
    }

    @Test
    fun `backend delegates lifecycle to process`() {
        val process = FakeAgentProcess(output = emptyList())
        val backend = ProcessAgentBackend(
            process,
            CliEventParser(),
            SteeringMode.NONE,
            BackendKind.CLAUDE_CLI,
            "Claude"
        )

        assertFalse(backend.isAlive)
        assertFalse(process.started)

        backend.start("session-123", listOf(
            SkillInfo("test", "plugin:test", "Test Skill", "test-plugin", "1.0", Paths.get("/test"), emptyList())
        ))
        assertTrue(process.started)
        assertEquals("session-123", process.lastResumeSessionId)

        backend.stop()
        assertTrue(process.stopped)
    }

    @Test
    fun `backend exposes provided properties`() {
        val process = FakeAgentProcess(output = emptyList())
        val backend = ProcessAgentBackend(
            process,
            CliEventParser(),
            SteeringMode.NATIVE,
            BackendKind.CODEX_APP_SERVER,
            "Codex"
        )

        assertEquals(BackendKind.CODEX_APP_SERVER, backend.backendKind)
        assertEquals("Codex", backend.agentLabel)
        assertEquals(SteeringMode.NATIVE, backend.steeringMode)
    }

    @Test
    fun `sendMessage escapes special characters`() {
        val process = FakeAgentProcess(output = emptyList())
        val backend = ProcessAgentBackend(
            process,
            CliEventParser(),
            SteeringMode.NONE,
            BackendKind.CLAUDE_CLI,
            "Claude"
        )
        backend.start(null, emptyList())

        backend.sendMessage("test\\quote\"newline\ntab\treturn\r")

        val written = process.lastWrite!!
        assertTrue(written.contains("test\\\\quote\\\"newline\\ntab\\treturn\\r"))
    }

    @Test
    fun `abort delegates to process interrupt`() {
        val process = FakeAgentProcess(output = emptyList())
        val backend = ProcessAgentBackend(
            process,
            CliEventParser(),
            SteeringMode.NONE,
            BackendKind.CLAUDE_CLI,
            "Claude"
        )
        backend.start(null, emptyList())

        assertFalse(process.interrupted)
        backend.abort()
        assertTrue(process.interrupted)
    }

    @Test
    fun `steer delegates to process`() {
        val process = FakeAgentProcess(output = emptyList(), steerSupported = true)
        val backend = ProcessAgentBackend(
            process,
            CliEventParser(),
            SteeringMode.NATIVE,
            BackendKind.CODEX_APP_SERVER,
            "Codex"
        )
        backend.start(null, emptyList())

        assertTrue(backend.steer("steer text"))
        assertEquals("steer text", process.lastSteerText)
    }

    @Test
    fun `recentErrors delegates to process`() {
        val process = FakeAgentProcess(
            output = emptyList(),
            stderr = listOf("error 1", "error 2")
        )
        val backend = ProcessAgentBackend(
            process,
            CliEventParser(),
            SteeringMode.NONE,
            BackendKind.CLAUDE_CLI,
            "Claude"
        )

        assertEquals(listOf("error 1", "error 2"), backend.recentErrors())
    }

    @Test
    fun `readEvent never parses blank lines`() {
        val spyParser = SpyEventParser(CliEvent.TextDelta("result"))
        val process = FakeAgentProcess(
            output = listOf("", "  ", """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"real"}}}""")
        )
        val backend = ProcessAgentBackend(
            process,
            spyParser,
            SteeringMode.NONE,
            BackendKind.CLAUDE_CLI,
            "Claude"
        )
        backend.start(null, emptyList())

        val event = backend.readEvent()

        // Assert parse was called exactly once with the non-blank line
        assertEquals(1, spyParser.parseCallCount)
        assertEquals("""{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"real"}}}""", spyParser.lastParsedLine)
        assertEquals(CliEvent.TextDelta("result"), event)
    }
}

/** Spy [AgentEventParser] test double that counts parse calls and records arguments. */
class SpyEventParser(private val returnEvent: CliEvent) : AgentEventParser {
    var parseCallCount = 0
    var lastParsedLine: String? = null

    override fun parse(jsonLine: String): CliEvent {
        parseCallCount++
        lastParsedLine = jsonLine
        return returnEvent
    }
}

/** Fake [AgentProcess] for testing that captures writes and feeds canned output. */
class FakeAgentProcess(
    private val output: List<String>,
    private val stderr: List<String> = emptyList(),
    private val steerSupported: Boolean = false
) : AgentProcess {
    private var outputIndex = 0

    var started = false
    var stopped = false
    var interrupted = false
    var lastWrite: String? = null
    var lastResumeSessionId: String? = null
    var lastSteerText: String? = null

    override val isAlive: Boolean get() = started && !stopped

    override fun start(resumeSessionId: String?, skills: List<SkillInfo>) {
        started = true
        lastResumeSessionId = resumeSessionId
    }

    override fun readLine(): String? {
        if (outputIndex >= output.size) return null
        return output[outputIndex++]
    }

    override fun writeLine(line: String) {
        lastWrite = line
    }

    override fun sendInterrupt() {
        interrupted = true
    }

    override fun stop() {
        stopped = true
    }

    override fun recentStderrLines(): List<String> = stderr

    override val supportsSteer: Boolean get() = steerSupported

    override fun steer(text: String): Boolean {
        if (!steerSupported) return false
        lastSteerText = text
        return true
    }
}
