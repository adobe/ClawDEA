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

import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.mcp.McpToolRouter
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.provider.openai.agent.AgentClient
import com.adobe.clawdea.provider.openai.agent.AgentCompletionRequest
import com.adobe.clawdea.provider.openai.agent.AgentLoopController
import com.adobe.clawdea.provider.openai.agent.AgentStreamEvent
import com.adobe.clawdea.provider.openai.agent.AgentRetryPolicy
import com.adobe.clawdea.provider.openai.agent.AgentToolExecutor
import com.adobe.clawdea.provider.openai.agent.ConversationState
import com.adobe.clawdea.provider.openai.agent.OpenAiInstructions
import com.adobe.clawdea.provider.openai.agent.OpenAiToolDefinition
import com.adobe.clawdea.provider.openai.agent.RetryContext
import com.adobe.clawdea.provider.openai.agent.RetryDecision
import com.adobe.clawdea.provider.openai.agent.SteeringController
import com.adobe.clawdea.provider.openai.agent.TurnResult
import com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore
import com.adobe.clawdea.provider.openai.client.OpenAiCompatibleClient
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
import com.adobe.clawdea.provider.openai.session.OpenAiSessionLedger
import com.adobe.clawdea.provider.openai.tools.HostPatchInput
import com.adobe.clawdea.provider.openai.tools.HostPatchTool
import com.adobe.clawdea.provider.openai.tools.HostShellTool
import com.adobe.clawdea.provider.openai.tools.MissingRouteBehavior
import com.adobe.clawdea.provider.openai.tools.OpenAiToolCatalog
import com.adobe.clawdea.provider.openai.tools.SharedToolApprovalGate
import com.adobe.clawdea.provider.openai.tools.ToolExecutionResult
import com.adobe.clawdea.skills.SkillInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP-based agent backend for OpenAI-compatible providers.
 *
 * Owns a coroutine scope, a [LinkedBlockingQueue] of [CliEvent]s, the [ConversationState],
 * and the [OpenAiSessionLedger]. Wraps [AgentLoopController] and forwards its emitted events.
 *
 * [readEvent] blocks on the queue (mirroring process-based backends).
 * [stop] cancels the scope and enqueues a sentinel so [readEvent] returns null (EOF).
 */
class OpenAiCompatibleAgentBackend(
    private val profile: ResolvedProviderProfile,
    private val credential: String,
    private val modelId: String,
    private val project: Project?,
    private val projectPath: String,
    private val mcpDefs: List<McpToolRouter.ToolDef>,
    private val approvalGate: SharedToolApprovalGate,
    private val autoAcceptEdits: () -> Boolean,
    override val agentLabel: String,
    private val readinessError: String? = null,
    ledger: OpenAiSessionLedger? = null, // Test seam: inject ledger with custom base dir
    // Test seam: construct the streaming client. Production default wraps [OpenAiCompatibleClient].
    private val clientFactory: (ResolvedProviderProfile, String) -> AgentClient =
        { p, cred -> HttpAgentClient(OpenAiCompatibleClient(), p, cred) },
    // Test seam: construct the tool executor. Production default dispatches to MCP + host tools.
    private val executorFactory: () -> AgentToolExecutor = { defaultExecutor(project, mcpDefs, approvalGate, autoAcceptEdits) },
    // Test seam: renew the profile credential (real path prompts on the EDT + runs the flow).
    // Returns true if a fresh credential was persisted. Default runs the EDT renewal flow.
    private val credentialRenewer: (() -> Boolean)? = null,
) : AgentBackend {

    private val log = Logger.getInstance(OpenAiCompatibleAgentBackend::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)
    // LinkedBlockingQueue forbids null elements, so EOF is signalled with a sentinel event
    // ([EOF_SENTINEL]) that [readEvent] translates back to null for the caller.
    private val queue = LinkedBlockingQueue<CliEvent>()
    private val state = ConversationState()
    private val ledger = ledger ?: OpenAiSessionLedger(projectPath)
    private val alive = AtomicBoolean(true)
    private var activeJob: Job? = null
    private val steeringController = SteeringController()
    // Current credential (mutable: a successful renewal replaces it for subsequent requests).
    @Volatile
    private var currentCredential: String = credential
    private val errors = mutableListOf<String>()
    private lateinit var sessionId: String

    override val isAlive: Boolean
        get() = alive.get()

    override val backendKind: BackendKind = BackendKind.OPENAI_COMPATIBLE_HTTP

    override val steeringMode: SteeringMode = SteeringMode.CANCEL_AND_CONTINUE

    override fun start(resumeSessionId: String?, skills: List<SkillInfo>) {
        if (readinessError != null) {
            // Not ready: emit error and sentinel
            queue.put(CliEvent.Result(
                text = readinessError,
                isError = true,
                costUsd = 0.0,
                sessionId = "",
            ))
            queue.put(EOF_SENTINEL) // EOF sentinel
            alive.set(false)
            return
        }

        // Build standing instructions. Degrade to empty when the platform Application is
        // unavailable (headless/unit-test contexts) — instructions are non-essential to the
        // turn/steering behavior and must not abort session start.
        val instructions = try {
            OpenAiInstructions.build(project, skills)
        } catch (e: Exception) {
            log.warn("Failed to build standing instructions; continuing without them", e)
            ""
        }

        // Determine session ID (resume or fresh)
        sessionId = resumeSessionId ?: "http-session-${System.currentTimeMillis()}"

        // Seed conversation state (fresh or resume from ledger)
        var isFreshSession = true
        if (resumeSessionId != null) {
            val resumeSuccess = resumeFromLedger(resumeSessionId)
            if (resumeSuccess) {
                log.info("Resumed session $resumeSessionId from ledger")
                isFreshSession = false
            } else {
                log.info("Failed to resume session $resumeSessionId; starting fresh")
            }
        }

        // Emit SystemInit
        val toolNames = mcpDefs.map { it.name }
        queue.put(CliEvent.SystemInit(
            sessionId = sessionId,
            model = modelId,
            tools = toolNames,
        ))

        // Add system/instructions message if present (only for fresh sessions)
        if (isFreshSession && instructions.isNotBlank()) {
            state.messages.add(com.adobe.clawdea.provider.openai.agent.AgentMessage(
                role = "system",
                content = instructions,
            ))
        }

        // Write meta record for fresh sessions
        if (isFreshSession) {
            writeMeta()
        }
    }

    private fun resumeFromLedger(resumeSessionId: String): Boolean {
        return try {
            val ledgerFile = ledgerFile(resumeSessionId)
            if (!ledgerFile.toFile().exists()) {
                return false
            }

            val loaded = com.adobe.clawdea.provider.openai.session.OpenAiSessionLedger.read(ledgerFile)
            if (loaded.records.isEmpty()) {
                return false
            }

            // Rebuild messages and completedToolCallIds
            val toolCallIdMap = mutableMapOf<Int, String>() // index -> id for tool_use records

            for (record in loaded.records) {
                when (record.type) {
                    "meta" -> {
                        // Skip meta record; we already have the sessionId
                    }
                    "user" -> {
                        val content = record.payload.get("content")?.asString ?: continue
                        state.messages.add(com.adobe.clawdea.provider.openai.agent.AgentMessage(role = "user", content = content))
                    }
                    "assistant" -> {
                        val content = record.payload.get("content")?.asString
                        state.messages.add(com.adobe.clawdea.provider.openai.agent.AgentMessage(role = "assistant", content = content))
                    }
                    "tool_use" -> {
                        val id = record.payload.get("id")?.asString ?: continue
                        val name = record.payload.get("name")?.asString ?: continue
                        val input = record.payload.get("input")?.asString ?: "{}"
                        val index = record.payload.get("index")?.asInt ?: 0

                        toolCallIdMap[index] = id

                        // Append tool call to the last assistant message
                        val lastAssistant = state.messages.lastOrNull { it.role == "assistant" }
                        if (lastAssistant != null) {
                            val updatedToolCalls = lastAssistant.toolCalls + com.adobe.clawdea.provider.openai.agent.AgentToolCall(id, name, input)
                            val updatedMessage = lastAssistant.copy(toolCalls = updatedToolCalls)
                            state.messages[state.messages.lastIndexOf(lastAssistant)] = updatedMessage
                        }
                    }
                    "tool_result" -> {
                        val toolUseId = record.payload.get("toolUseId")?.asString ?: continue
                        val content = record.payload.get("content")?.asString ?: ""

                        state.messages.add(com.adobe.clawdea.provider.openai.agent.AgentMessage(
                            role = "tool",
                            content = content,
                            toolCallId = toolUseId,
                        ))
                        state.completedToolCallIds.add(toolUseId)
                    }
                    // Skip reasoning, usage — not needed for state rebuild
                }
            }

            true
        } catch (e: Exception) {
            log.warn("Failed to resume from ledger", e)
            false
        }
    }

    private fun ledgerFile(sessionId: String): java.nio.file.Path {
        val canonicalProjectPath = java.io.File(projectPath).canonicalPath
        val projectSubdir = com.adobe.clawdea.provider.openai.session.OpenAiSessionLedger.projectDirName(canonicalProjectPath)
        val baseDir = java.io.File(System.getProperty("user.home"), ".clawdea/sessions").toPath()
        return baseDir.resolve(projectSubdir).resolve("$sessionId.jsonl")
    }

    private fun writeMeta() {
        val payload = com.google.gson.JsonObject().apply {
            addProperty("sessionId", sessionId)
            addProperty("profileId", profile.profile.id)
            addProperty("projectPath", java.io.File(projectPath).canonicalPath)
            addProperty("model", modelId)
            addProperty("createdAt", java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString())
        }
        val record = com.adobe.clawdea.provider.openai.session.SessionLedgerRecord(
            type = "meta",
            timestamp = java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
            payload = payload,
        )
        ledger.append(sessionId, record)
    }

    override fun sendMessage(text: String) {
        if (!alive.get()) {
            log.warn("sendMessage called on stopped backend")
            return
        }

        // Cancel any active turn (abort semantics for a brand-new user message)
        activeJob?.cancel()

        // Start new turn in background. The outer job owns the cancel-and-continue orchestration
        // loop; each round runs in a lazily-started CHILD job so mid-turn steering can cancel just
        // that round (via SteeringController) without tearing down the orchestration loop.
        activeJob = scope.launch {
            // Most-recently-registered round job, so the finally can compare-and-clear only the job
            // this turn actually owns (never a newer turn's job).
            var lastTurnJob: Job? = null
            try {
                writeUserMessage(text)

                var turnText = text
                var appendUserMessage = true

                while (true) {
                    // Lazy child so setActiveJob() happens-before the turn body runs — closes the
                    // race where steer() could arrive before the round's job is registered.
                    val roundText = turnText
                    val roundAppend = appendUserMessage
                    val turnJob = launch(start = CoroutineStart.LAZY) {
                        runTurnWithRetries(roundText, roundAppend)
                    }
                    lastTurnJob = turnJob
                    steeringController.setActiveJob(turnJob)
                    turnJob.start()
                    turnJob.join()

                    // Atomically decide (under one lock) whether a steer is pending. If so, continue
                    // WITHOUT emitting the cancelled round's Result; otherwise the active job is
                    // compare-and-cleared here, closing the natural-completion race where a
                    // concurrent steer() could otherwise land on a dead job and be silently dropped.
                    val steer = steeringController.consumePendingSteerOrClear(turnJob)
                    if (steer == null) {
                        break
                    }

                    // Persist any valid partial assistant text from the cancelled round so the
                    // continuation turn sees the redirected conversation. Incomplete tool-call
                    // fragments live in the cancelled round's local assembler and are discarded.
                    val partial = state.partialAssistantText
                    if (partial.isNotBlank()) {
                        state.messages.add(com.adobe.clawdea.provider.openai.agent.AgentMessage(
                            role = "assistant",
                            content = partial,
                        ))
                        writeAssistant(partial)
                    }
                    state.partialAssistantText = ""

                    writeUserMessage(steer)
                    turnText = steer
                    appendUserMessage = true
                }
            } catch (e: CancellationException) {
                // Whole-turn abort (new message / stop): unwind cleanly, no terminal Result.
                throw e
            } catch (e: Exception) {
                log.warn("Turn error", e)
                errors.add(e.message ?: "unknown error")
                queue.put(CliEvent.Result(
                    text = "Turn error: ${e.message}",
                    isError = true,
                    costUsd = 0.0,
                    sessionId = "",
                ))
            } finally {
                // Compare-and-clear: only null the active job if it's still this turn's job, so a
                // newer sendMessage that already re-registered its own job is not clobbered.
                steeringController.clearActiveJob(lastTurnJob)
            }
        }
    }

    /**
     * Run a single round with bounded retries. Delegates streaming to [AgentLoopController.runTurn]
     * (which emits the terminal [CliEvent.Result] for natural terminal states) and applies
     * [AgentRetryPolicy] only when a request stream FAILS. On a terminal decision it emits exactly
     * one terminal error [CliEvent.Result] here.
     */
    private suspend fun runTurnWithRetries(text: String, appendUserMessage: Boolean) {
        var attempts = 0
        var authRenewals = 0
        var append = appendUserMessage

        while (true) {
            val loop = AgentLoopController(
                client = clientFactory(profile, currentCredential),
                executor = executorFactory(),
                state = state,
                maxToolRounds = 10,
                maxElapsedMs = 600_000,
                maxContextChars = 1_000_000,
                modelId = modelId,
                tools = agentToolDefinitions(mcpDefs),
            )

            val result = runOneRound(loop, text, append)

            // Natural terminal state (success or limit): runTurn already emitted the Result.
            if (!result.streamFailed) {
                return
            }

            val ctx = RetryContext(
                status = result.status,
                retryAfterSeconds = result.retryAfterSeconds,
                emittedText = result.emittedText,
                executedTools = result.executedTools,
                authRenewals = authRenewals,
                attempts = attempts,
            )

            when (val decision = AgentRetryPolicy.decide(ctx)) {
                is RetryDecision.RetryAfter -> {
                    delay(decision.delayMillis)
                    attempts++
                    append = false // user message already in state; re-issue the same request
                }
                RetryDecision.RenewCredentialOnce -> {
                    val renewed = renewCredential()
                    if (renewed) {
                        authRenewals++
                        append = false
                    } else {
                        emitTerminalError("Authentication failed. Credential renewal failed or was cancelled.")
                        return
                    }
                }
                RetryDecision.AskUser -> {
                    val retry = promptPartialRetry(result)
                    if (retry) {
                        attempts++
                        append = false
                    } else {
                        emitTerminalError(result.finalText.ifBlank { "Request failed after partial output." })
                        return
                    }
                }
                RetryDecision.Fail -> {
                    emitTerminalError(result.finalText.ifBlank { "Request failed." })
                    return
                }
            }
        }
    }

    /** Run one streaming round, forwarding events to the queue and persisting ledger records. */
    private suspend fun runOneRound(loop: AgentLoopController, text: String, append: Boolean): TurnResult {
        val reasoningBuffer = StringBuilder()
        var assistantTextBuffer = ""

        return loop.runTurn(text, appendUserMessage = append) { event ->
            when (event) {
                is CliEvent.TextDelta -> {
                    assistantTextBuffer += event.text
                }
                is CliEvent.ReasoningDelta -> {
                    if (event.summary) {
                        reasoningBuffer.append(event.text)
                        writeReasoning(reasoningBuffer.toString())
                        reasoningBuffer.clear()
                    }
                }
                is CliEvent.AssistantMessage -> {
                    if (assistantTextBuffer.isNotEmpty()) {
                        writeAssistant(assistantTextBuffer)
                    }
                    event.toolUses.forEachIndexed { index, toolUse ->
                        writeToolUse(toolUse.id, toolUse.name, toolUse.input, index)
                    }
                    assistantTextBuffer = ""
                }
                is CliEvent.ToolResult -> {
                    writeToolResult(event.toolUseId, event.content, event.isError)
                }
                is CliEvent.Result -> {
                    writeUsage()
                }
                else -> {
                    // Skip SystemInit and other events
                }
            }
            queue.put(event)
        }
    }

    private fun emitTerminalError(message: String) {
        errors.add(message)
        writeUsage()
        queue.put(CliEvent.Result(
            text = message,
            isError = true,
            costUsd = 0.0,
            sessionId = sessionId,
            inputTokens = state.usage.inputTokens,
            outputTokens = state.usage.outputTokens,
            cacheReadTokens = state.usage.cachedInputTokens,
            cacheCreationTokens = 0,
        ))
    }

    /**
     * Renew the profile credential. Uses the injected [credentialRenewer] when provided (tests),
     * otherwise runs the real EDT prompt + credential flow and re-reads the persisted credential.
     * Fails closed (returns false) when headless.
     */
    private fun renewCredential(): Boolean {
        val renewer = credentialRenewer ?: { renewCredentialViaEdt() }
        val renewed = renewer()
        if (renewed) {
            // Re-read the freshly persisted credential for subsequent requests.
            currentCredential = ProfileCredentialStore().get(profile.profile.id).ifBlank { currentCredential }
        }
        return renewed
    }

    /**
     * Prompt the user (EDT) with [PartialRetryPrompt] to confirm retrying after partial output.
     * Returns true if the user chose Retry. Fails closed (returns false) when headless.
     */
    private fun promptPartialRetry(result: TurnResult): Boolean {
        val proj = project ?: return false
        val decision = java.util.concurrent.atomic.AtomicBoolean(false)
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
            decision.set(
                com.adobe.clawdea.chat.PartialRetryPrompt(
                    project = proj,
                    profileName = profile.profile.name,
                    modelId = modelId,
                    emittedText = result.emittedText,
                    toolsCompleted = result.executedTools,
                ).showAndGet(),
            )
        }
        return decision.get()
    }

    /**
     * Real credential-renewal path. Delegates to [CredentialRenewalCoordinator] (the brief's named
     * integration point): the coordinator's prompt lambda shows a [CredentialRenewalDialog] on the
     * EDT (a field per declared credential input, password field when secret), runs the credential
     * flow on this worker thread via [CredentialFlowExecutor], persists the fresh credential, and
     * clears the secret CharArrays in its own finally. Fails closed (false) when headless or when
     * the profile declares no credential inputs.
     */
    private fun renewCredentialViaEdt(): Boolean {
        val proj = project ?: return false
        val flowProfile = profile.profile
        if (flowProfile.credentialFlow.inputs.isEmpty()) return false

        val settings = com.adobe.clawdea.settings.ClawDEASettings.getInstance()
        val coordinator = com.adobe.clawdea.provider.openai.auth.CredentialRenewalCoordinator(
            profileStore = com.adobe.clawdea.provider.openai.profile.ProfileStore(settings),
            prompt = { p ->
                val ref = com.intellij.openapi.util.Ref.create<com.adobe.clawdea.provider.openai.auth.CredentialPromptResult?>()
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                    ref.set(com.adobe.clawdea.chat.CredentialRenewalDialog(proj, p).promptForCredentials())
                }
                ref.get()
            },
            executor = com.adobe.clawdea.provider.openai.auth.CredentialFlowExecutor(
                transport = com.adobe.clawdea.provider.openai.auth.JdkProfileHttpTransport(),
                credentialStore = ProfileCredentialStore(),
            ),
            configuredValues = { profile.configuredValues },
            environment = { System.getenv() },
        )
        return coordinator.renew(flowProfile.id)
    }

    private fun writeUserMessage(content: String) {
        val payload = com.google.gson.JsonObject().apply {
            addProperty("content", content)
        }
        val record = com.adobe.clawdea.provider.openai.session.SessionLedgerRecord(
            type = "user",
            timestamp = java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
            payload = payload,
        )
        ledger.append(sessionId, record)
    }

    private fun writeAssistant(content: String) {
        val payload = com.google.gson.JsonObject().apply {
            addProperty("content", content)
        }
        val record = com.adobe.clawdea.provider.openai.session.SessionLedgerRecord(
            type = "assistant",
            timestamp = java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
            payload = payload,
        )
        ledger.append(sessionId, record)
    }

    private fun writeReasoning(summary: String) {
        val payload = com.google.gson.JsonObject().apply {
            addProperty("summary", summary)
        }
        val record = com.adobe.clawdea.provider.openai.session.SessionLedgerRecord(
            type = "reasoning",
            timestamp = java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
            payload = payload,
        )
        ledger.append(sessionId, record)
    }

    private fun writeToolUse(id: String, name: String, input: String, index: Int) {
        val payload = com.google.gson.JsonObject().apply {
            addProperty("id", id)
            addProperty("name", name)
            addProperty("input", input)
            addProperty("index", index)
        }
        val record = com.adobe.clawdea.provider.openai.session.SessionLedgerRecord(
            type = "tool_use",
            timestamp = java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
            payload = payload,
        )
        ledger.append(sessionId, record)
    }

    private fun writeToolResult(toolUseId: String, content: String, isError: Boolean) {
        val payload = com.google.gson.JsonObject().apply {
            addProperty("toolUseId", toolUseId)
            addProperty("content", content)
            addProperty("isError", isError)
        }
        val record = com.adobe.clawdea.provider.openai.session.SessionLedgerRecord(
            type = "tool_result",
            timestamp = java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
            payload = payload,
        )
        ledger.append(sessionId, record)
    }

    private fun writeUsage() {
        val payload = com.google.gson.JsonObject().apply {
            addProperty("inputTokens", state.usage.inputTokens)
            addProperty("outputTokens", state.usage.outputTokens)
            addProperty("cachedInputTokens", state.usage.cachedInputTokens)
            addProperty("reasoningTokens", state.usage.reasoningTokens)
        }
        val record = com.adobe.clawdea.provider.openai.session.SessionLedgerRecord(
            type = "usage",
            timestamp = java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
            payload = payload,
        )
        ledger.append(sessionId, record)
    }

    /**
     * Read the next event from the queue. Blocks until an event or EOF sentinel is available.
     *
     * **Persistent backend contract**: This backend is PERSISTENT like the Codex backend.
     * Between turns, `readEvent()` blocking on `queue.take()` is the INTENDED behavior — it's
     * waiting for the user's next message. The EOF sentinel ([EOF_SENTINEL], translated back to
     * null by [readEvent]) is enqueued ONLY by [stop], never on a per-turn error. A turn error
     * emits an error [CliEvent.Result]
     * and the session stays alive for the next user message.
     */
    override fun readEvent(): CliEvent? {
        val event = queue.take() // Blocks until an event or sentinel
        return if (event === EOF_SENTINEL) null else event
    }

    override fun abort() {
        activeJob?.cancel()
        activeJob = null
    }

    /**
     * Cancel-and-continue steering: cancels the running round and queues [text] as the next user
     * turn. Returns false when no turn is active. The orchestration loop in [sendMessage] observes
     * the pending steer, preserves partial assistant text, discards incomplete tool fragments, and
     * launches the continuation WITHOUT emitting a terminal Result for the cancelled round.
     */
    override fun steer(text: String): Boolean {
        return runBlocking { steeringController.steer(text) }
    }

    override fun stop() {
        alive.set(false)
        activeJob?.cancel()
        queue.put(EOF_SENTINEL) // EOF sentinel
    }

    override fun recentErrors(): List<String> {
        return errors.takeLast(5)
    }

    private companion object {
        /** Non-null placeholder used to signal EOF through the null-hostile [LinkedBlockingQueue]. */
        private val EOF_SENTINEL = CliEvent.Result(
            text = "",
            isError = false,
            costUsd = 0.0,
            sessionId = "",
        )
    }
}

/**
 * Tool definitions advertised to agentic models: the MCP catalog PLUS the host tools
 * ([HostShellTool] as `Bash`, [HostPatchTool] as `apply_patch`) that [ProductionToolExecutor]
 * dispatches directly. Must stay in lockstep with what the executor can route, or the model will
 * emit tool calls the backend cannot fulfil.
 */
internal fun agentToolDefinitions(mcpDefs: List<McpToolRouter.ToolDef>): List<OpenAiToolDefinition> =
    OpenAiToolCatalog(mcpDefs, emptyList()).definitions() + OpenAiToolCatalog.hostToolDefinitions()

/**
 * Build the production tool executor: dispatches to MCP tools + host shell/patch tools.
 * Extracted to a top-level function so the backend's `executorFactory` default can reference it.
 */
internal fun defaultExecutor(
    project: Project?,
    mcpDefs: List<McpToolRouter.ToolDef>,
    approvalGate: SharedToolApprovalGate,
    autoAcceptEdits: () -> Boolean,
): AgentToolExecutor = ProductionToolExecutor(
    catalog = OpenAiToolCatalog(mcpDefs, emptyList()),
    shellTool = if (project != null) HostShellTool(project, approvalGate, missingRouteBehavior = MissingRouteBehavior.DENY) else null,
    patchTool = if (project != null) HostPatchTool(project, autoAcceptEdits, approvalGate) else null,
)

/**
 * [AgentClient] adapter for [OpenAiCompatibleClient].
 */
internal class HttpAgentClient(
    private val httpClient: OpenAiCompatibleClient,
    private val profile: ResolvedProviderProfile,
    private val credential: String,
) : AgentClient {
    override suspend fun stream(request: AgentCompletionRequest): Flow<AgentStreamEvent> {
        return httpClient.streamAgentCompletion(profile, credential, request)
    }
}

/**
 * [AgentToolExecutor] that dispatches to MCP tools + host shell/patch tools.
 */
private class ProductionToolExecutor(
    private val catalog: OpenAiToolCatalog,
    private val shellTool: HostShellTool?,
    private val patchTool: HostPatchTool?,
) : AgentToolExecutor {
    private val gson = com.google.gson.Gson()

    override fun execute(toolCall: com.adobe.clawdea.provider.openai.agent.AgentToolCall): ToolExecutionResult {
        // Route to host tools or catalog
        return when (toolCall.name) {
            "Bash" -> shellTool?.let { tool ->
                val command = try {
                    com.google.gson.JsonParser.parseString(toolCall.argumentsJson)
                        .asJsonObject.get("command")?.asString
                } catch (e: Exception) {
                    return ToolExecutionResult(toolCall.id, "Malformed Bash arguments: ${e.message}", true)
                }
                if (command == null) {
                    ToolExecutionResult(toolCall.id, "missing required parameter: command", true)
                } else {
                    tool.execute(command, toolCall.id)
                }
            } ?: ToolExecutionResult(toolCall.id, "Shell tool not available", true)
            "apply_patch" -> patchTool?.let { tool ->
                val input = try {
                    parsePatchInput(toolCall.argumentsJson)
                } catch (e: Exception) {
                    return ToolExecutionResult(toolCall.id, "Malformed apply_patch arguments: ${e.message}", true)
                }
                tool.execute(input, toolCall.id)
            } ?: ToolExecutionResult(toolCall.id, "Patch tool not available", true)
            else -> catalog.dispatch(toolCall.id, toolCall.name, toolCall.argumentsJson)
        }
    }

    private fun parsePatchInput(argumentsJson: String): HostPatchInput {
        val obj = com.google.gson.JsonParser.parseString(argumentsJson).asJsonObject
        val filePath = obj.get("file_path")?.asString
            ?: throw IllegalArgumentException("missing required parameter: file_path")
        // original_content defaults to empty (new file); proposed_content is required.
        val originalContent = obj.get("original_content")?.asString ?: ""
        val proposedContent = obj.get("proposed_content")?.asString
            ?: throw IllegalArgumentException("missing required parameter: proposed_content")
        return HostPatchInput(
            filePath = filePath,
            originalContent = originalContent,
            proposedContent = proposedContent,
        )
    }
}
