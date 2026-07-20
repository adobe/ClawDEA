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
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.skills.SkillInfo

/**
 * [AgentBackend] adapter for process-based agents (Claude CLI, Codex app-server).
 * Wraps an [AgentProcess] + [AgentEventParser] pair and owns the Claude user-envelope
 * serialization that [CliBridge] currently does inline.
 */
class ProcessAgentBackend(
    /**
     * The wrapped process. Exposed as a deliberate escape hatch for CliBridge's
     * UnavailableAgentProcess / expectedExitGeneration bookkeeping. Callers should prefer
     * [AgentBackend] methods otherwise.
     */
    val process: AgentProcess,
    private val parser: AgentEventParser,
    override val steeringMode: SteeringMode,
    override val backendKind: BackendKind,
    override val agentLabel: String,
) : AgentBackend {

    override val isAlive: Boolean
        get() = process.isAlive

    override fun start(resumeSessionId: String?, skills: List<SkillInfo>) {
        process.start(resumeSessionId, skills)
    }

    /**
     * Reads the next event from the process, skipping blank lines (preserving the bridge's
     * current `if (line.isBlank()) continue` behavior). Returns null on EOF.
     *
     * Parsing happens before the bridge's generation check, but this is safe: a stale reader
     * can only exist when CliBridge has already called stop() (which closes the stream), so
     * readLine() returns null before any real line is parsed. The start()-is-gated-on-!isRunning
     * invariant ensures generation never advances while a process is alive and emitting lines.
     */
    override fun readEvent(): CliEvent? {
        while (true) {
            val line = process.readLine() ?: return null
            if (line.isBlank()) continue
            return parser.parse(line)
        }
    }

    /**
     * Serializes [text] into the Claude user-envelope JSON format (`{"type":"user","message":{"role":"user","content":"$escaped"}}`)
     * and writes it to the process. This is the exact escape sequence that [CliBridge.sendMessage] currently does inline.
     */
    override fun sendMessage(text: String) {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

        val json = """{"type":"user","message":{"role":"user","content":"$escaped"}}"""
        process.writeLine(json)
    }

    override fun abort() {
        process.sendInterrupt()
    }

    override fun steer(text: String): Boolean {
        return process.steer(text)
    }

    override fun stop() {
        process.stop()
    }

    override fun recentErrors(): List<String> {
        return process.recentStderrLines()
    }
}
