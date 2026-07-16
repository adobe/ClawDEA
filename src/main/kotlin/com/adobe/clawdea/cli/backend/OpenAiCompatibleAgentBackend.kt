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
import com.adobe.clawdea.provider.openai.agent.AgentToolExecutor
import com.adobe.clawdea.provider.openai.agent.ConversationState
import com.adobe.clawdea.provider.openai.agent.OpenAiInstructions
import com.adobe.clawdea.provider.openai.client.OpenAiCompatibleClient
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
import com.adobe.clawdea.provider.openai.session.OpenAiSessionLedger
import com.adobe.clawdea.provider.openai.tools.HostPatchTool
import com.adobe.clawdea.provider.openai.tools.HostShellTool
import com.adobe.clawdea.provider.openai.tools.MissingRouteBehavior
import com.adobe.clawdea.provider.openai.tools.OpenAiToolCatalog
import com.adobe.clawdea.provider.openai.tools.SharedToolApprovalGate
import com.adobe.clawdea.provider.openai.tools.ToolExecutionResult
import com.adobe.clawdea.skills.SkillInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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
) : AgentBackend {

    private val log = Logger.getInstance(OpenAiCompatibleAgentBackend::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val queue = LinkedBlockingQueue<CliEvent?>()
    private val state = ConversationState()
    private val ledger = ledger ?: OpenAiSessionLedger(projectPath)
    private val httpClient = OpenAiCompatibleClient()
    private val alive = AtomicBoolean(true)
    private var activeJob: Job? = null
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
            queue.put(null) // EOF sentinel
            alive.set(false)
            return
        }

        // Build standing instructions
        val instructions = OpenAiInstructions.build(project, skills)

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

        // Cancel any active turn
        activeJob?.cancel()

        // Start new turn in background
        activeJob = scope.launch {
            try {
                // Write user message to ledger
                writeUserMessage(text)

                val client = HttpAgentClient(httpClient, profile, credential)
                val executor = ProductionToolExecutor(
                    catalog = OpenAiToolCatalog(mcpDefs, emptyList()),
                    shellTool = if (project != null) HostShellTool(project, approvalGate, missingRouteBehavior = MissingRouteBehavior.DENY) else null,
                    patchTool = if (project != null) HostPatchTool(project, autoAcceptEdits, approvalGate) else null,
                )

                val loop = AgentLoopController(
                    client = client,
                    executor = executor,
                    state = state,
                    maxToolRounds = 10,
                    maxElapsedMs = 600_000,
                    maxContextChars = 1_000_000,
                    modelId = modelId,
                )

                // Buffer for reasoning and assistant text
                val reasoningBuffer = StringBuilder()
                var assistantTextBuffer = ""

                loop.runTurn(text) { event ->
                    // Write to ledger based on event type
                    when (event) {
                        is CliEvent.TextDelta -> {
                            assistantTextBuffer += event.text
                        }
                        is CliEvent.ReasoningDelta -> {
                            if (event.summary) {
                                // Complete reasoning block
                                reasoningBuffer.append(event.text)
                                writeReasoning(reasoningBuffer.toString())
                                reasoningBuffer.clear()
                            }
                        }
                        is CliEvent.AssistantMessage -> {
                            // Write assistant text
                            if (assistantTextBuffer.isNotEmpty()) {
                                writeAssistant(assistantTextBuffer)
                            }
                            // Write tool_use records
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
                            // Skip SystemInit, other events
                        }
                    }
                    queue.put(event)
                }
            } catch (e: Exception) {
                log.warn("Turn error", e)
                errors.add(e.message ?: "unknown error")
                queue.put(CliEvent.Result(
                    text = "Turn error: ${e.message}",
                    isError = true,
                    costUsd = 0.0,
                    sessionId = "",
                ))
            }
        }
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
     * waiting for the user's next message. The EOF sentinel (`queue.put(null)`) is enqueued
     * ONLY by [stop], never on a per-turn error. A turn error emits an error [CliEvent.Result]
     * and the session stays alive for the next user message.
     */
    override fun readEvent(): CliEvent? {
        return queue.take() // Blocks until an event or sentinel
    }

    override fun abort() {
        activeJob?.cancel()
        activeJob = null
    }

    override fun steer(text: String): Boolean {
        // Task 6 implements steering via CANCEL_AND_CONTINUE
        // For now, return false (not implemented)
        return false
    }

    override fun stop() {
        alive.set(false)
        activeJob?.cancel()
        queue.put(null) // EOF sentinel
    }

    override fun recentErrors(): List<String> {
        return errors.takeLast(5)
    }
}

/**
 * [AgentClient] adapter for [OpenAiCompatibleClient].
 */
private class HttpAgentClient(
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
    override fun execute(toolCall: com.adobe.clawdea.provider.openai.agent.AgentToolCall): ToolExecutionResult {
        // Route to host tools or catalog
        return when (toolCall.name) {
            "Bash" -> shellTool?.execute(toolCall.argumentsJson, toolCall.id)
                ?: ToolExecutionResult(toolCall.id, "Shell tool not available", true)
            "apply_patch" -> {
                // Parse HostPatchInput from argumentsJson
                // For now, return error (host patch integration is complex)
                ToolExecutionResult(toolCall.id, "Patch tool not yet integrated", true)
            }
            else -> catalog.dispatch(toolCall.id, toolCall.name, toolCall.argumentsJson)
        }
    }
}
