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
import com.google.gson.JsonParser

/**
 * [SubAgentRunner] for the OpenAI-compatible backend: dispatches the `Agent` tool as a depth-1
 * sub-agent that runs its own nested [AgentLoopController] turn. Child events are re-emitted with
 * `parentToolUseId` set to the dispatching tool_use id so the chat's existing sub-agent card groups
 * them (see [com.adobe.clawdea.chat.SubAgentController] / EventStreamHandler).
 *
 * The sub-agent is given the same tools as the main turn MINUS the Agent tool itself — dispatch is
 * depth-1 (no recursion), matching the depth-1 model the card UI already assumes.
 *
 * Constructed per parent turn so it captures the current model/credential/tools. Kept free of
 * IntelliJ concerns (client/executor/tools injected) so it is unit-testable with fakes.
 */
class SubAgentDispatcher(
    private val client: AgentClient,
    private val executor: AgentToolExecutor,
    private val tools: List<OpenAiToolDefinition>,
    private val modelId: String,
    private val systemPrompt: String?,
    private val streaming: Boolean,
    private val maxToolRounds: Int,
    private val maxElapsedMs: Long,
    private val maxContextChars: Int = 1_000_000,
) : SubAgentRunner {

    override val toolName: String = "Agent"

    override suspend fun run(toolCall: AgentToolCall, emit: (CliEvent) -> Unit): ToolExecutionResult {
        val prompt = try {
            val obj = JsonParser.parseString(toolCall.argumentsJson).asJsonObject
            obj.get("prompt")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                ?: return ToolExecutionResult(toolCall.id, "missing required parameter: prompt", true)
        } catch (e: Exception) {
            return ToolExecutionResult(toolCall.id, "Malformed Agent arguments: ${e.message}", true)
        }

        val state = ConversationState()
        if (!systemPrompt.isNullOrBlank()) {
            state.messages.add(AgentMessage(role = "system", content = systemPrompt))
        }

        // Re-tag every child event with the dispatching tool_use id so it renders inside the card.
        // The nested loop's own terminal Result is swallowed here — the PARENT loop emits the
        // card-finalizing ToolResult (parent=null) from the value we return.
        val childEmit: (CliEvent) -> Unit = { child ->
            val tagged: CliEvent? = when (child) {
                is CliEvent.TextDelta -> child.copy(parentToolUseId = toolCall.id)
                is CliEvent.AssistantMessage -> child.copy(parentToolUseId = toolCall.id)
                is CliEvent.ToolResult -> child.copy(parentToolUseId = toolCall.id)
                is CliEvent.Result -> null
                else -> child
            }
            if (tagged != null) emit(tagged)
        }

        val loop = AgentLoopController(
            client = client,
            executor = executor,
            state = state,
            maxToolRounds = maxToolRounds,
            maxElapsedMs = maxElapsedMs,
            maxContextChars = maxContextChars,
            modelId = modelId,
            tools = tools,
            stream = streaming,
            // No subAgentRunner → the sub-agent cannot dispatch further sub-agents (depth-1).
        )

        var terminalError: String? = null
        val turn = try {
            loop.runTurn(prompt, appendUserMessage = true) { event ->
                if (event is CliEvent.Result && event.isError) terminalError = event.text
                childEmit(event)
            }
        } catch (e: Exception) {
            return ToolExecutionResult(toolCall.id, "Sub-agent failed: ${e.message}", true)
        }

        val err = terminalError
        return when {
            err != null -> ToolExecutionResult(toolCall.id, err, true)
            turn.streamFailed -> ToolExecutionResult(toolCall.id, turn.finalText.ifBlank { "Sub-agent request failed." }, true)
            turn.isError -> ToolExecutionResult(toolCall.id, turn.finalText.ifBlank { "Sub-agent turn error." }, true)
            turn.finalText.isBlank() -> ToolExecutionResult(toolCall.id, "(sub-agent produced no output)", false)
            else -> ToolExecutionResult(toolCall.id, turn.finalText, false)
        }
    }
}
