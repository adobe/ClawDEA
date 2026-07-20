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
import com.adobe.clawdea.cli.backend.AgentBackend
import com.adobe.clawdea.cli.backend.AgentBackendFactory
import com.adobe.clawdea.cli.backend.SteeringMode
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.provider.ProviderRegistry
import com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore
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
    selection: AgentSelection? = null,
    settings: ClawDEASettings = ClawDEASettings.getInstance(),
    credentialStore: ProfileCredentialStore = ProfileCredentialStore(),
    // Test seam: resolve the effective provider id. Production uses AuthManager's fallthrough
    // (byte-identical to the pre-per-tab CliBridge, which applies env-fallthrough: a configured
    // provider lacking creds resolves to a credentialed one). Tests inject a fixed id.
    effectiveProviderIdProvider: () -> String = { AuthManager.getInstance().effectiveProviderId() },
) : Disposable {

    private val log = Logger.getInstance(CliBridge::class.java)

    // Choose the agentic backend once, at construction. When an explicit selection is provided,
    // use it; when null, fall back to the effective provider (preserving today's behavior). A
    // provider switch requires a session/bridge restart (ChatSession recreates this), which
    // re-runs the selection.
    private val resolvedSelection: AgentSelection =
        selection ?: computeDefaultSelection(effectiveProviderIdProvider(), settings, workingDirectory)

    private val backend: AgentBackend = AgentBackendFactory.create(
        resolvedSelection,
        workingDirectory,
        mcpPort,
        project,
        settings,
        credentialStore,
    )

    /**
     * The [AgentSelection] that this bridge was constructed from (effective selection, resolved from
     * the default when null was passed). Exposed for T6/tests to read.
     */
    val selection: AgentSelection
        get() = resolvedSelection

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
        get() = backendKind == BackendKind.CODEX_APP_SERVER

    /** Exact backend process kind, fixed for the bridge's lifetime. */
    val backendKind: BackendKind
        get() = backend.backendKind

    /**
     * Human-readable name of the backend this bridge actually runs ("Codex" / "Claude"), fixed at
     * construction. Prefer this over [AgentLabel.current] for anything tied to the *running* session:
     * the effective provider can change in settings mid-session while the bridge keeps its backend.
     */
    val agentLabel: String
        get() = backend.agentLabel

    val isRunning: Boolean
        get() = backend.isAlive

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

        backend.start(requestedResumeSessionId, skills)

        readerJob = scope.launch {
            try {
                while (isActive && isCurrentReader(readerGeneration) && backend.isAlive) {
                    val event = backend.readEvent() ?: break
                    if (!isCurrentReader(readerGeneration)) break

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
                        // UnavailableAgentProcess emits a result then exits immediately
                        if (backend is com.adobe.clawdea.cli.backend.ProcessAgentBackend &&
                            backend.process is UnavailableAgentProcess) {
                            expectedExitGeneration = readerGeneration
                        }
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

        backend.sendMessage(outgoing)
    }

    /** Prepends any pending replay transcript to the first user turn, then clears it. */
    private fun firstMessagePayload(text: String): String {
        val replay = pendingReplayContext ?: return text
        pendingReplayContext = null
        return com.adobe.clawdea.chat.session.TranscriptReplay.wrapFirstMessage(replay, text)
    }

    fun abort() {
        expectedExitGeneration = activeGeneration
        backend.abort()
    }

    /**
     * True when the backend supports mid-turn steering — either native (Codex `turn/steer`) or
     * cancel-and-continue (OpenAI-compatible HTTP). False only for backends with no steer primitive.
     */
    val supportsSteer: Boolean
        get() = backend.steeringMode != SteeringMode.NONE

    /**
     * Injects [text] into the running turn. Native backends steer without interrupting; the
     * OpenAI-compatible backend cancels the running round and continues with the steer message.
     * Returns true when the backend accepted it into a live turn; false when there is no steerable
     * turn and the caller should send a normal new message instead.
     */
    fun steer(text: String): Boolean = backend.steer(text)

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
        backend.stop()
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
        /**
         * Computes the default AgentSelection when none is explicitly provided. The [effectiveProviderId]
         * is resolved by the caller (production: AuthManager's env-fallthrough; tests: a fixed id).
         * For openai-compatible, includes the active profile + selected model from [settings]; for
         * others, profileId=null and modelId="" (they resolve model in their backend branch). This
         * mirrors the legacy delegating `AgentBackendFactory.create(providerId, …)` overload.
         */
        private fun computeDefaultSelection(
            effectiveProviderId: String,
            settings: ClawDEASettings,
            workingDirectory: String,
        ): AgentSelection {
            return if (effectiveProviderId == ProviderRegistry.OPENAI_COMPATIBLE_ID) {
                val profileId = settings.state.activeOpenAiCompatibleProfileId
                val catalogKey = ProviderRegistry.catalogKey(effectiveProviderId, profileId)
                val modelId = settings.getSelectedModelId(workingDirectory, catalogKey) ?: ""
                AgentSelection(effectiveProviderId, profileId.ifBlank { null }, modelId)
            } else {
                AgentSelection(effectiveProviderId)
            }
        }

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

        /** Compatibility delegate for callers that only distinguish Codex from other backends. */
        internal fun isCodexProvider(providerId: String): Boolean = ProviderRegistry.isCodex(providerId)

        internal fun requiresBackendRebuild(
            currentKind: BackendKind,
            newProviderId: String,
        ): Boolean = currentKind != ProviderRegistry.require(newProviderId).backendKind
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
        if (!shouldRecoverFromRejectedResume(resumeSessionId, backend.recentErrors())) {
            return false
        }

        log.info("CLI rejected resume session $resumeSessionId; restarting fresh")
        expectedExitGeneration = readerGeneration
        sessionId = null
        backend.stop()
        start(resumeSessionId = null, skills = skills)
        return true
    }
}
