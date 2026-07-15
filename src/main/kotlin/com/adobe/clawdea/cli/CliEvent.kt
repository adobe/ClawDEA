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

sealed class CliEvent {
    data class SystemInit(
        val sessionId: String,
        val model: String,
        val tools: List<String>,
    ) : CliEvent()

    data class TextDelta(
        val text: String,
        val parentToolUseId: String? = null,
    ) : CliEvent()

    /**
     * A streamed chunk of the model's reasoning/thinking (codex `item/reasoning/textDelta` and
     * `item/reasoning/summaryTextDelta`). Claude's `stream-json` drops thinking content, so this is
     * codex-only today; [EventStreamHandler] buffers it and renders a collapsible "Thinking" section
     * ahead of the answer. [summary] marks the condensed reasoning summary vs the raw chain.
     */
    data class ReasoningDelta(
        val text: String,
        val summary: Boolean = false,
    ) : CliEvent()

    data class AssistantMessage(
        val text: String,
        val toolUses: List<ToolUse>,
        val parentToolUseId: String? = null,
        /**
         * Model id from `message.model`. Every assistant message carries it, so this is
         * the reliable source for the cost footer — more robust than the SystemInit
         * model, which is blank on resume and absent in some CC init shapes.
         */
        val model: String = "",
    ) : CliEvent()

    data class ToolUse(
        val id: String,
        val name: String,
        val input: String,
    )

    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false,
        val parentToolUseId: String? = null,
    ) : CliEvent()

    data class Result(
        val text: String,
        val isError: Boolean,
        val costUsd: Double,
        val sessionId: String,
        /**
         * Total context tokens consumed at the end of this turn, as reported by CC's
         * `result` event `usage` object. Sum of `input_tokens` + `cache_read_input_tokens`
         * + `cache_creation_input_tokens`. Zero if unknown (older CC versions or stream
         * format changes). This is the authoritative number for the context-budget
         * indicator — replaces our stream-side estimation which double-counted tool
         * outputs and never decayed.
         */
        val contextTokens: Int = 0,
        /**
         * Context window size for the model used this turn, from `modelUsage.<model>.contextWindow`.
         * Zero if unknown — caller should fall back to a default. Critical for Opus 4.7 (1M)
         * which would otherwise be measured against the 200K Sonnet/Opus default.
         */
        val contextWindow: Int = 0,
        // Per-turn token breakdown from the result `usage` object. Used to compute a notional cost when total_cost_usd is 0 (subscription/bedrock flat-rate plans).
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val cacheReadTokens: Int = 0,
        val cacheCreationTokens: Int = 0,
    ) : CliEvent()

    data class Unknown(
        val rawType: String,
        val rawJson: String,
    ) : CliEvent()

    data class AuthFailure(
        val reason: String,
    ) : CliEvent()

    /**
     * A `/goal` Stop-hook evaluation surfaced by the CLI between auto-continued
     * turns. The CLI injects these as `type:"user"` text messages of the form
     * `Stop hook feedback:\n[<condition>]: <reason>`.
     */
    data class GoalFeedback(
        val condition: String,
        val reason: String,
    ) : CliEvent()

    sealed class TaskEvent : CliEvent() {
        data class TaskCreated(
            val id: String,
            val subject: String,
            val description: String,
            val activeForm: String?,
        ) : TaskEvent()

        data class TaskStatusChanged(
            val id: String,
            val newStatus: String,
        ) : TaskEvent()

        data class TaskDeleted(
            val id: String,
        ) : TaskEvent()
    }
}
