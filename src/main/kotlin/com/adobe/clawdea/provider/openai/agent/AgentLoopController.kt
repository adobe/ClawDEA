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

/** Why a soft limit checkpoint fired. */
enum class SoftLimitReason { ROUNDS, TIME }

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
 * Runs a dispatched sub-agent (the `Agent` tool). Unlike [AgentToolExecutor] this is `suspend` and
 * receives the parent loop's [emit] channel so the sub-agent's inner steps can stream as child
 * events (each tagged with the dispatching tool_use id as their parentToolUseId). Returns the
 * sub-agent's final report as the tool result. Kept separate from [AgentToolExecutor] so the
 * synchronous executor and all its implementors are unaffected.
 */
interface SubAgentRunner {
    /** The tool name that triggers a sub-agent dispatch. Must match the chat's card detection. */
    val toolName: String
    suspend fun run(toolCall: AgentToolCall, emit: (CliEvent) -> Unit): ToolExecutionResult
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
    // Soft-limit checkpoint. Called when a NON-ZERO round/time ceiling trips. Returns true to
    // continue (counter resets), false to stop the turn cleanly. Default always continues.
    private val onSoftLimit: suspend (SoftLimitReason, Int) -> Boolean = { _, _ -> true },
    // When non-null, an over-threshold context triggers compaction instead of a fatal error.
    private val compactor: ConversationCompactor? = null,
    // The model's context window in tokens, or null to use the char fallback budget.
    private val contextWindowTokens: Int? = null,
    private val compactionThreshold: Double = 0.8,
    // Notice callback after a successful compaction; arg = number of messages summarized.
    private val onCompacted: (Int) -> Unit = {},
    // When non-null and a tool call names [SubAgentRunner.toolName], the loop routes it to this
    // runner (suspend, with the emit channel) instead of the synchronous [executor]. Null means no
    // sub-agent dispatch is offered (the Agent tool is also not advertised in that case).
    private val subAgentRunner: SubAgentRunner? = null,
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
        var turnStart = System.currentTimeMillis()

        // A prior turn may have Stopped at a round checkpoint right after appending an assistant
        // tool_calls message but before executing the tools (those tools never ran). Such a message
        // has no following tool results, which violates the OpenAI structure invariant on the next
        // request. Drop it so this turn starts from a clean boundary.
        val last = state.messages.lastOrNull()
        if (last != null && last.role == "assistant" && last.toolCalls.isNotEmpty()) {
            state.messages.removeAt(state.messages.size - 1)
        }

        // Add user message. On a bounded retry of a failed request, the user message is already
        // in [state.messages] from the first attempt, so the caller passes appendUserMessage=false
        // to re-issue the exact same request without duplicating the turn's user turn.
        if (appendUserMessage) {
            state.messages.add(AgentMessage(role = "user", content = userText))
        }

        // Returns true if the loop should continue (counter reset by caller), false if the turn ended
        // (this method emitted the terminal Result).
        suspend fun checkpoint(reason: SoftLimitReason, count: Int): Boolean {
            val cont = onSoftLimit(reason, count)
            if (cont) return true
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
            return false
        }

        var toolRounds = 0
        // Turn-level output tracking (spans all rounds): used to build a RetryContext when a
        // later round's stream fails after earlier rounds already produced output.
        var emittedTextOverall = false
        var executedToolsOverall = false
        // Breaks "same failing tool call, forever" degeneration loops (weak models). Per-turn.
        val loopGuard = ToolCallLoopGuard()
        // Guard to prevent re-compaction at an unchanged usage total (when the provider emits no
        // Usage event post-compaction, the token reading freezes until the next response).
        var lastCompactionUsageTotal = -1

        while (true) {
            // Check time limit (0 = unlimited)
            if (maxElapsedMs > 0 && System.currentTimeMillis() - turnStart > maxElapsedMs) {
                val elapsedMin = ((System.currentTimeMillis() - turnStart) / 60_000).toInt()
                if (!checkpoint(SoftLimitReason.TIME, elapsedMin)) {
                    return TurnResult(isError = false, toolRounds = toolRounds, finalText = state.partialAssistantText)
                }
                turnStart = System.currentTimeMillis() // reset window and continue
            }

            // Context budget: compact when over threshold (never fatal if a compactor is present).
            val contextChars = state.messages.sumOf { (it.content?.length ?: 0) }
            val usageTotal = state.usage.inputTokens + state.usage.outputTokens
            val overThreshold = when (val window = contextWindowTokens) {
                null -> contextChars >= maxContextChars * compactionThreshold
                else -> if (usageTotal > 0) {
                    // Token path: usage reported. Compact once usage crosses the window threshold,
                    // guarded so it doesn't re-fire at an unchanged usage total.
                    usageTotal >= window * compactionThreshold && usageTotal != lastCompactionUsageTotal
                } else {
                    // Usage not reported by this provider: estimate tokens from chars (~4 chars/token)
                    // against the SAME window, so compaction still fires instead of never triggering.
                    (contextChars / CHARS_PER_TOKEN_ESTIMATE) >= window * compactionThreshold
                }
            }
            if (overThreshold) {
                val compacted = compactor?.let { c ->
                    try {
                        val r = c.compact(state.messages.toList(), keepTailTarget = COMPACT_KEEP_TAIL)
                        state.messages.clear()
                        state.messages.addAll(r.messages)
                        state.completedToolCallIds.removeAll(r.evictedToolCallIds)
                        onCompacted(r.summarizedCount)
                        lastCompactionUsageTotal = usageTotal
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("compaction failed, falling back to fatal context result", e)
                        false
                    }
                } ?: false
                if (!compacted) {
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

            // Tool round limit (0 = unlimited). Check BEFORE incrementing.
            if (maxToolRounds > 0 && toolRounds >= maxToolRounds) {
                if (!checkpoint(SoftLimitReason.ROUNDS, toolRounds)) {
                    return TurnResult(isError = false, toolRounds = toolRounds, finalText = state.partialAssistantText)
                }
                toolRounds = 0 // reset window and continue
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

                val runner = subAgentRunner
                val isSubAgent = runner != null && toolCall.name == runner.toolName
                val signature = loopGuard.signature(toolCall.name, toolCall.argumentsJson)

                // Loop-breaker: a run of identical FAILING calls (weak models retrying a call that
                // keeps erroring) escalates nudge → stop. Sub-agent dispatches are exempt — their
                // repetition is not this degeneration pattern.
                val decision = if (isSubAgent) ToolCallLoopGuard.Decision.EXECUTE else loopGuard.decide(signature)
                if (decision == ToolCallLoopGuard.Decision.STOP) {
                    val msg = "Stopped: repeated identical failing call to `${toolCall.name}`."
                    emit(CliEvent.ToolResult(toolCall.id, msg, isError = true))
                    emit(CliEvent.Result(
                        text = state.partialAssistantText.ifBlank { msg },
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

                val result = if (decision == ToolCallLoopGuard.Decision.NUDGE) {
                    // Don't re-execute; feed back a corrective message and count it as another repeat.
                    loopGuard.recordNudge(signature)
                    ToolExecutionResult(toolCall.id, loopGuard.nudgeMessage(toolCall.name, loopGuard.repeatCount(signature)), isError = true)
                } else {
                    // Execute. A sub-agent dispatch runs the nested loop (suspend, streaming child
                    // events through emit); everything else goes to the synchronous executor.
                    val r = if (isSubAgent) runner!!.run(toolCall, emit) else executor.execute(toolCall)
                    if (!isSubAgent) loopGuard.recordResult(signature, r.isError)
                    r
                }
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

    companion object {
        private const val COMPACT_KEEP_TAIL = 6
        // Rough chars-per-token used only when a provider reports no usage but a token window is
        // known, so char-measured context can still be compared against a token budget.
        private const val CHARS_PER_TOKEN_ESTIMATE = 4
    }
}
