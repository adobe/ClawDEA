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

    // Tracks which process generation was deliberately asked to exit (e.g. via
    // restart or abort). This is generation-scoped so a stale reader from the
    // old process cannot emit a crash after a new CLI process starts.
    @Volatile
    private var expectedExitGeneration: Long? = null

    @Volatile
    private var activeGeneration: Long = 0

    var sessionId: String? = null
        private set

    val isRunning: Boolean
        get() = cliProcess.isAlive

    fun start(resumeSessionId: String? = null, skills: List<SkillInfo> = emptyList()) {
        if (isRunning) return

        val readerGeneration = synchronized(this) {
            activeGeneration += 1
            activeGeneration
        }
        val requestedResumeSessionId = resumableSessionForStart(resumeSessionId)
        sessionId = requestedResumeSessionId

        cliProcess.start(requestedResumeSessionId, skills)

        readerJob = scope.launch {
            try {
                while (isActive && isCurrentReader(readerGeneration) && cliProcess.isAlive) {
                    val line = cliProcess.readLine() ?: break
                    if (!isCurrentReader(readerGeneration)) break
                    if (line.isBlank()) continue

                    val event = parser.parse(line)

                    if (event is CliEvent.AuthFailure) {
                        onAuthFailure(event.reason)
                    }

                    if (event is CliEvent.Result) {
                        sessionId = sessionAfterResult(sessionId, event.sessionId)
                    }

                    logToolEvent(event)

                    // Suppress events after a deliberate abort — the CLI emits its
                    // own error Result on SIGINT which would otherwise fire a
                    // confusing notification and prematurely end the paused UI.
                    if (!canReaderEmit(readerGeneration)) continue

                    _events.emit(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (canReaderEmit(readerGeneration)) {
                    log.warn("Error reading CLI events", e)
                    _events.emit(CliEvent.Unknown(rawType = "", rawJson = """{"error":"${e.message}"}"""))
                }
            }

            if (isActive && shouldEmitUnexpectedExit(readerGeneration, activeGeneration, expectedExitGeneration)) {
                if (recoverFromRejectedResume(requestedResumeSessionId, readerGeneration, skills)) return@launch

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
        expectedExitGeneration = activeGeneration
        cliProcess.sendInterrupt()
    }

    fun restart(resumeSessionId: String? = null, skills: List<SkillInfo> = emptyList()) {
        val sessionToResume = resumeSessionForRestart(sessionId, resumeSessionId)
        stop()
        start(sessionToResume, skills)
    }

    fun restartFresh(skills: List<SkillInfo> = emptyList()) {
        stop()
        start(resumeSessionId = null, skills = skills)
    }

    fun stop() {
        // Mark as expected so the reader's synthetic "exited unexpectedly"
        // Result event is suppressed — otherwise it can race a subsequent
        // start() and wipe the "Connected" status the new CLI just set.
        expectedExitGeneration = activeGeneration
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

    companion object {
        internal fun resumeSessionForRestart(
            currentSessionId: String?,
            requestedResumeSessionId: String?,
        ): String? =
            requestedResumeSessionId?.takeIf { it.isNotBlank() }
                ?: currentSessionId?.takeIf { it.isNotBlank() }

        internal fun resumableSessionForStart(resumeSessionId: String?): String? =
            resumeSessionId?.takeIf { it.isNotBlank() }

        internal fun shouldEmitUnexpectedExit(
            readerGeneration: Long,
            activeGeneration: Long,
            expectedExitGeneration: Long?,
        ): Boolean =
            readerGeneration == activeGeneration && expectedExitGeneration != readerGeneration

        internal fun shouldRecoverFromRejectedResume(
            requestedResumeSessionId: String?,
            recentStderr: List<String>,
        ): Boolean {
            val sessionId = requestedResumeSessionId?.takeIf { it.isNotBlank() } ?: return false
            return recentStderr.any { line ->
                line.contains("No conversation found with session ID: $sessionId")
            }
        }

        internal fun sessionAfterResult(
            currentSessionId: String?,
            resultSessionId: String,
        ): String? =
            resultSessionId.takeIf { it.isNotBlank() }
                ?: currentSessionId?.takeIf { it.isNotBlank() }
    }

    private fun isCurrentReader(readerGeneration: Long): Boolean =
        readerGeneration == activeGeneration

    private fun canReaderEmit(readerGeneration: Long): Boolean =
        shouldEmitUnexpectedExit(readerGeneration, activeGeneration, expectedExitGeneration)

    private fun recoverFromRejectedResume(
        resumeSessionId: String?,
        readerGeneration: Long,
        skills: List<SkillInfo>,
    ): Boolean {
        if (!shouldRecoverFromRejectedResume(resumeSessionId, cliProcess.recentStderrLines())) {
            return false
        }

        log.info("CLI rejected resume session $resumeSessionId; restarting fresh")
        expectedExitGeneration = readerGeneration
        sessionId = null
        cliProcess.stop()
        start(resumeSessionId = null, skills = skills)
        return true
    }
}
