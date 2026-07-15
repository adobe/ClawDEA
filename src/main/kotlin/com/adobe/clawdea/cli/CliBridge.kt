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

import com.adobe.clawdea.auth.AuthManager
import com.adobe.clawdea.settings.ClawDEASettings
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

    // Choose the agentic backend once, at construction, from the effective provider.
    // OpenAI providers drive the `codex` CLI; everything else drives `claude`. A provider
    // switch requires a session/bridge restart (ChatSession recreates this), which re-runs
    // the selection.
    private val effectiveProviderId: String = AuthManager.getInstance().effectiveProviderId()
    private val useCodexBackend: Boolean = isCodexProvider(effectiveProviderId)

    private val agentProcess: AgentProcess =
        if (useCodexBackend) CodexAppServerProcess(workingDirectory, mcpPort, project)
        else CliProcess(workingDirectory, mcpPort, project)

    private val parser: AgentEventParser =
        if (useCodexBackend) {
            CodexAppServerParser(ClawDEASettings.getInstance().getCliModelId(workingDirectory, effectiveProviderId))
        } else {
            CliEventParser()
        }

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

    /**
     * Prior-conversation transcript to prepend to the *first* user message of this session, set by a
     * cross-backend resume (see [com.adobe.clawdea.chat.session.TranscriptReplay]). Consumed and
     * cleared by the first [sendMessage]; null for native resumes and fresh sessions.
     */
    @Volatile
    private var pendingReplayContext: String? = null

    /** True when this bridge drives the `codex` CLI (OpenAI providers). Fixed for the bridge's life. */
    val usesCodexBackend: Boolean
        get() = useCodexBackend

    /**
     * Human-readable name of the backend this bridge actually runs ("Codex" / "Claude"), fixed at
     * construction. Prefer this over [AgentLabel.current] for anything tied to the *running* session:
     * the effective provider can change in settings mid-session while the bridge keeps its backend.
     */
    val agentLabel: String
        get() = if (useCodexBackend) "Codex" else "Claude"

    val isRunning: Boolean
        get() = agentProcess.isAlive

    fun start(
        resumeSessionId: String? = null,
        skills: List<SkillInfo> = emptyList(),
        replayContext: String? = null,
    ) {
        if (isRunning) return

        val readerGeneration = synchronized(this) {
            activeGeneration += 1
            activeGeneration
        }
        val requestedResumeSessionId = resumableSessionForStart(resumeSessionId)
        sessionId = requestedResumeSessionId
        pendingReplayContext = replayContext?.takeIf { it.isNotBlank() }

        agentProcess.start(requestedResumeSessionId, skills)

        readerJob = scope.launch {
            try {
                while (isActive && isCurrentReader(readerGeneration) && agentProcess.isAlive) {
                    val line = agentProcess.readLine() ?: break
                    if (!isCurrentReader(readerGeneration)) break
                    if (line.isBlank()) continue

                    val event = parser.parse(line)

                    if (event is CliEvent.AuthFailure) {
                        onAuthFailure(event.reason)
                    }

                    // Capture the session id from init as well as result. Claude also reports it
                    // on the terminal result, but codex only emits it once (thread.started ->
                    // SystemInit); tracking it here is what lets a codex turn `exec resume`.
                    if (event is CliEvent.SystemInit && event.sessionId.isNotBlank()) {
                        sessionId = event.sessionId
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
        // On a cross-backend resume, the first message carries the prior conversation as context so
        // the new backend can continue it (neither CLI can natively resume the other's session).
        val outgoing = firstMessagePayload(text)

        val escaped = outgoing
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

        val json = """{"type":"user","message":{"role":"user","content":"$escaped"}}"""
        agentProcess.writeLine(json)
    }

    /** Prepends any pending replay transcript to the first user turn, then clears it. */
    private fun firstMessagePayload(text: String): String {
        val replay = pendingReplayContext ?: return text
        pendingReplayContext = null
        return com.adobe.clawdea.chat.session.TranscriptReplay.wrapFirstMessage(replay, text)
    }

    fun abort() {
        expectedExitGeneration = activeGeneration
        agentProcess.sendInterrupt()
    }

    /** True when the backend supports native mid-turn steering (codex `turn/steer`). */
    val supportsSteer: Boolean
        get() = agentProcess.supportsSteer

    /**
     * Injects [text] into the running turn without interrupting it (native steer). Returns true
     * when the backend accepted it into a live turn; false when there is no steerable turn and the
     * caller should send a normal new message instead.
     */
    fun steer(text: String): Boolean = agentProcess.steer(text)

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
        agentProcess.stop()
        sessionId = null
        pendingReplayContext = null
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

        /** OpenAI providers are backed by the `codex` CLI; everything else by `claude`. */
        internal fun isCodexProvider(providerId: String): Boolean =
            providerId == "openai" || providerId == "openai-subscription"
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
        if (!shouldRecoverFromRejectedResume(resumeSessionId, agentProcess.recentStderrLines())) {
            return false
        }

        log.info("CLI rejected resume session $resumeSessionId; restarting fresh")
        expectedExitGeneration = readerGeneration
        sessionId = null
        agentProcess.stop()
        start(resumeSessionId = null, skills = skills)
        return true
    }
}
