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

import com.adobe.clawdea.skills.SkillInfo
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class CliBridge(
    private val workingDirectory: String,
    mcpPort: Int = 0,
    private val onAuthFailure: (reason: String) -> Unit = {},
    private val project: Project? = null,
) : Disposable {

    private val log = Logger.getInstance(CliBridge::class.java)

    private val cliProcess = CliProcess(workingDirectory, mcpPort, project)
    private val parser = CliEventParser()

    private val _events = MutableSharedFlow<CliEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<CliEvent> = _events

    private var readerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Set when the caller deliberately triggered the CLI to exit (e.g. via
    // abort → SIGINT in -p mode). Suppresses the synthetic "exited unexpectedly"
    // Result event so the UI doesn't treat a pause as a crash.
    @Volatile
    private var expectedExit: Boolean = false

    var sessionId: String? = null
        private set

    val isRunning: Boolean
        get() = cliProcess.isAlive

    fun start(resumeSessionId: String? = null, skills: List<SkillInfo> = emptyList()) {
        if (isRunning) return

        expectedExit = false
        cliProcess.start(resumeSessionId, skills)

        readerJob = scope.launch {
            try {
                while (isActive && cliProcess.isAlive) {
                    val line = cliProcess.readLine() ?: break
                    if (line.isBlank()) continue

                    val event = parser.parse(line)

                    if (event is CliEvent.AuthFailure) {
                        onAuthFailure(event.reason)
                    }

                    if (event is CliEvent.SystemInit) {
                        sessionId = event.sessionId
                    }

                    logToolEvent(event)

                    // Suppress events after a deliberate abort — the CLI emits its
                    // own error Result on SIGINT which would otherwise fire a
                    // confusing notification and prematurely end the paused UI.
                    if (expectedExit) continue

                    _events.emit(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!expectedExit) {
                    log.warn("Error reading CLI events", e)
                    _events.emit(CliEvent.Unknown(rawType = "", rawJson = """{"error":"${e.message}"}"""))
                }
            }

            if (isActive && !expectedExit) {
                _events.emit(CliEvent.Result(
                    text = "CLI process exited unexpectedly",
                    isError = true,
                    costUsd = 0.0,
                    sessionId = sessionId ?: "",
                ))
            }
        }
    }

    fun sendMessage(text: String) {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

        val json = """{"type":"user","message":{"role":"user","content":"$escaped"}}"""
        cliProcess.writeLine(json)
    }

    fun abort() {
        expectedExit = true
        cliProcess.sendInterrupt()
    }

    fun restart(resumeSessionId: String? = null, skills: List<SkillInfo> = emptyList()) {
        stop()
        start(resumeSessionId, skills)
    }

    fun stop() {
        // Mark as expected so the reader's synthetic "exited unexpectedly"
        // Result event is suppressed — otherwise it can race a subsequent
        // start() and wipe the "Connected" status the new CLI just set.
        expectedExit = true
        readerJob?.cancel()
        readerJob = null
        cliProcess.stop()
        sessionId = null
    }

    override fun dispose() {
        stop()
        scope.cancel()
    }

    private fun logToolEvent(event: CliEvent) {
        when (event) {
            is CliEvent.AssistantMessage -> {
                for (use in event.toolUses) {
                    log.info("cli tool_use name=${use.name} id=${use.id} input_len=${use.input.length}")
                }
            }
            is CliEvent.ToolResult -> {
                val status = if (event.isError) "error" else "ok"
                log.info("cli tool_result id=${event.toolUseId} $status content_len=${event.content.length}")
            }
            else -> {}
        }
    }
}
