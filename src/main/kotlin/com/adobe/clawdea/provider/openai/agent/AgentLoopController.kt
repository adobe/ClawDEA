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
package com.adobe.clawdea.provider.openai.agent

import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.provider.openai.tools.ToolExecutionResult
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * Injectable interface for HTTP agent client (returns streaming events).
 */
interface AgentClient {
    suspend fun stream(request: AgentCompletionRequest): Flow<AgentStreamEvent>
}

/**
 * Injectable interface for tool execution (dispatch tool calls).
 */
interface AgentToolExecutor {
    fun execute(toolCall: AgentToolCall): ToolExecutionResult
}

/**
 * Result of a single turn.
 *
 * [streamFailed] is true only when a streaming request failed (HTTP [status] error, remote
 * [AgentStreamEvent.Failure], or a thrown transport exception) BEFORE the turn reached a natural
 * terminal state. When [streamFailed] is true the controller does NOT emit a terminal
 * [CliEvent.Result]; the backend inspects [status]/[retryAfterSeconds]/[emittedText]/[executedTools]
 * to build a [RetryContext] and decide whether to retry, renew, ask the user, or emit the terminal
 * error itself. For every other terminal outcome (success, time/context/tool-round limit) the
 * controller has already emitted the terminal [CliEvent.Result] and [streamFailed] is false.
 */
data class TurnResult(
    val isError: Boolean,
    val toolRounds: Int,
    val finalText: String = "",
    val status: Int? = null,
    val retryAfterSeconds: Long? = null,
    val emittedText: Boolean = false,
    val executedTools: Boolean = false,
    val streamFailed: Boolean = false,
)

/**
 * Pure multi-round agent loop controller. Drives streaming chat completions with tool calling,
 * enforcing exactly-once execution and safety limits.
 *
 * ### Loop invariants
 * - Tool calls already in [ConversationState.completedToolCallIds] are skipped (never executed twice).
 * - After [maxToolRounds] rounds of tool calling, returns [TurnResult] with `isError=true`.
 * - Wall-clock timeout ([maxElapsedMs]) and context budget ([maxContextChars]) are enforced.
 *
 * ### Emission
 * The loop emits [CliEvent]s via the [emit] callback: TextDelta, ReasoningDelta (buffered summary),
 * AssistantMessage (before tool execution), ToolResult (after execution), and terminal Result.
 *
 * ### Design
 * Kept pure of IntelliJ/coroutine-scope concerns so it's testable with injected fakes.
 * The [OpenAiCompatibleAgentBackend] wraps this and forwards emitted events to a queue.
 */
class AgentLoopController(
    private val client: AgentClient,
    private val executor: AgentToolExecutor,
    private val state: ConversationState,
    private val maxToolRounds: Int,
    private val maxElapsedMs: Long,
    private val maxContextChars: Int,
    private val modelId: String = "",
    // Tool definitions advertised to the model on every request. Must match what [executor] can
    // dispatch (MCP tools + host Bash/apply_patch). Empty means the model is told of no tools.
    private val tools: List<OpenAiToolDefinition> = emptyList(),
    // Whether to request streamed (SSE) completions. When false, the request sets `stream:false`;
    // the client auto-detects the single-JSON response and parses it via parseNonStreamedCompletion.
    // Defaulted true so existing tests/constructions are unaffected.
    private val stream: Boolean = true,
) {

    private val log = Logger.getInstance(AgentLoopController::class.java)

    /**
     * Run a single user turn: add user message, stream completion(s), execute tools, repeat until
     * no tool calls remain. Returns [TurnResult] with success/error status and tool round count.
     */
    suspend fun runTurn(
        userText: String,
        appendUserMessage: Boolean = true,
        emit: (CliEvent) -> Unit,
    ): TurnResult {
        val turnStart = System.currentTimeMillis()

        // Add user message. On a bounded retry of a failed request, the user message is already
        // in [state.messages] from the first attempt, so the caller passes appendUserMessage=false
        // to re-issue the exact same request without duplicating the turn's user turn.
        if (appendUserMessage) {
            state.messages.add(AgentMessage(role = "user", content = userText))
        }

        var toolRounds = 0
        // Turn-level output tracking (spans all rounds): used to build a RetryContext when a
        // later round's stream fails after earlier rounds already produced output.
        var emittedTextOverall = false
        var executedToolsOverall = false

        while (true) {
            // Check time limit
            if (System.currentTimeMillis() - turnStart > maxElapsedMs) {
                emit(CliEvent.Result(
                    text = "Turn exceeded time limit",
                    isError = true,
                    costUsd = 0.0,
                    sessionId = "",
                    inputTokens = state.usage.inputTokens,
                    outputTokens = state.usage.outputTokens,
                    cacheReadTokens = state.usage.cachedInputTokens,
                    cacheCreationTokens = 0,
                    reasoningTokens = state.usage.reasoningTokens,
                    contextWindow = 0,
                ))
                return TurnResult(isError = true, toolRounds = toolRounds)
            }

            // Check context budget (approximate: sum all message content lengths)
            val contextSize = state.messages.sumOf { (it.content?.length ?: 0) }
            if (contextSize > maxContextChars) {
                emit(CliEvent.Result(
                    text = "Context budget exceeded",
                    isError = true,
                    costUsd = 0.0,
                    sessionId = "",
                    inputTokens = state.usage.inputTokens,
                    outputTokens = state.usage.outputTokens,
                    cacheReadTokens = state.usage.cachedInputTokens,
                    reasoningTokens = state.usage.reasoningTokens,
                    cacheCreationTokens = 0,
                    contextWindow = 0,
                ))
                return TurnResult(isError = true, toolRounds = toolRounds)
            }

            // Build request
            val request = AgentCompletionRequest(
                model = modelId,
                messages = state.messages.toList(),
                tools = tools,
                maxTokens = 4096,
                stream = stream,
            )

            // Stream completion
            state.partialAssistantText = ""
            val assembler = ToolCallAssembler()
            var finishReason: String? = null
            val reasoning = StringBuilder()
            var streamError: String? = null
            var failureStatus: Int? = null
            var failureRetryAfter: Long? = null

            // Diagnostic counters (never content): let the empty-answer symptom be read off idea.log.
            var parsedEvents = 0
            var textDeltas = 0
            var reasoningEvents = 0
            var toolFragments = 0
            var usageEvents = 0
            var failureEvents = 0

            try {
                client.stream(request).collect { event ->
                    parsedEvents++
                    when (event) {
                        is AgentStreamEvent.Text -> {
                            textDeltas++
                            state.partialAssistantText += event.text
                            emittedTextOverall = true
                            emit(CliEvent.TextDelta(event.text))
                        }
                        is AgentStreamEvent.Reasoning -> {
                            reasoningEvents++
                            reasoning.append(event.text)
                        }
                        is AgentStreamEvent.ToolFragment -> {
                            toolFragments++
                            assembler.accept(event)
                        }
                        is AgentStreamEvent.Usage -> {
                            usageEvents++
                            state.usage = AgentUsage(
                                inputTokens = event.inputTokens,
                                outputTokens = event.outputTokens,
                                cachedInputTokens = event.cachedInputTokens,
                                reasoningTokens = event.reasoningTokens,
                            )
                        }
                        is AgentStreamEvent.Finished -> {
                            finishReason = event.reason
                        }
                        is AgentStreamEvent.Failure -> {
                            failureEvents++
                            streamError = event.message
                            failureStatus = event.status
                            failureRetryAfter = event.retryAfterSeconds
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Steering (or shutdown) cancelled this round. Do NOT swallow it: rethrow so
                // structured concurrency unwinds and the backend's join() observes completion.
                // The incomplete assembler is a local that never reaches .completed(), so its
                // partial tool fragments are discarded; the persisted partialAssistantText is
                // preserved by the backend for the continuation turn.
                throw e
            } catch (e: Exception) {
                streamError = e.message ?: "unknown error"
            }

            // Diagnostic (DEBUG, counts/lengths only — never prompt or generated content): reveals
            // why a turn came back empty (e.g. text-deltas=0 reasoning>0 => reasoning-only model;
            // events=0 => wrong endpoint/model; failure!=null => remote error). Enable debug logging
            // for this category and grep idea.log for "openai-compatible turn:".
            log.debug(
                "openai-compatible turn: events=$parsedEvents text-deltas=$textDeltas " +
                    "reasoning=$reasoningEvents tool-frags=$toolFragments usage=$usageEvents " +
                    "failures=$failureEvents finish=${finishReason ?: "none"} " +
                    "assistantChars=${state.partialAssistantText.length}" +
                    (failureStatus?.let { " failureStatus=$it" } ?: "")
            )

            // Handle stream errors WITHOUT emitting a terminal Result: the backend inspects the
            // returned TurnResult and drives retry / renew / ask-user / terminal-error itself.
            if (streamError != null) {
                return TurnResult(
                    isError = true,
                    toolRounds = toolRounds,
                    finalText = streamError ?: "",
                    status = failureStatus,
                    retryAfterSeconds = failureRetryAfter,
                    emittedText = emittedTextOverall,
                    executedTools = executedToolsOverall,
                    streamFailed = true,
                )
            }

            // Emit reasoning summary if present
            if (reasoning.isNotEmpty()) {
                emit(CliEvent.ReasoningDelta(reasoning.toString(), summary = true))
            }

            // Assemble tool calls
            val toolCalls = assembler.completed()

            // Append assistant message
            val assistantMessage = AgentMessage(
                role = "assistant",
                content = state.partialAssistantText.ifEmpty { null },
                toolCalls = toolCalls,
            )
            state.messages.add(assistantMessage)

            // Emit AssistantMessage BEFORE execution
            emit(CliEvent.AssistantMessage(
                text = state.partialAssistantText,
                toolUses = toolCalls.map { CliEvent.ToolUse(it.id, it.name, it.argumentsJson) },
                model = modelId,
            ))

            // If no tool calls, we're done
            if (toolCalls.isEmpty()) {
                emit(CliEvent.Result(
                    text = state.partialAssistantText,
                    isError = false,
                    costUsd = 0.0,
                    sessionId = "",
                    inputTokens = state.usage.inputTokens,
                    outputTokens = state.usage.outputTokens,
                    cacheReadTokens = state.usage.cachedInputTokens,
                    cacheCreationTokens = 0,
                    reasoningTokens = state.usage.reasoningTokens,
                    contextWindow = 0,
                ))
                return TurnResult(isError = false, toolRounds = toolRounds, finalText = state.partialAssistantText)
            }

            // Check tool round limit BEFORE incrementing
            if (toolRounds >= maxToolRounds) {
                emit(CliEvent.Result(
                    text = "Tool round limit exceeded",
                    isError = true,
                    costUsd = 0.0,
                    sessionId = "",
                    inputTokens = state.usage.inputTokens,
                    outputTokens = state.usage.outputTokens,
                    cacheReadTokens = state.usage.cachedInputTokens,
                    reasoningTokens = state.usage.reasoningTokens,
                    cacheCreationTokens = 0,
                    contextWindow = 0,
                ))
                return TurnResult(isError = true, toolRounds = toolRounds)
            }
            toolRounds++

            // Execute tool calls (exactly-once: skip if already completed)
            for (toolCall in toolCalls) {
                if (toolCall.id in state.completedToolCallIds) {
                    // Skip execution, emit cached result placeholder
                    emit(CliEvent.ToolResult(
                        toolUseId = toolCall.id,
                        content = "[already executed]",
                        isError = false,
                    ))
                    continue
                }

                // Execute
                val result = executor.execute(toolCall)
                executedToolsOverall = true
                state.completedToolCallIds.add(toolCall.id)

                // Append tool result message
                state.messages.add(AgentMessage(
                    role = "tool",
                    content = result.content,
                    toolCallId = toolCall.id,
                ))

                // Emit ToolResult
                emit(CliEvent.ToolResult(
                    toolUseId = result.toolCallId,
                    content = result.content,
                    isError = result.isError,
                ))
            }

            // Continue loop for next round
        }
    }
}
