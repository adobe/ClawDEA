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
    ) : CliEvent()

    data class AssistantMessage(
        val text: String,
        val toolUses: List<ToolUse>,
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
    ) : CliEvent()

    data class Unknown(
        val rawType: String,
        val rawJson: String,
    ) : CliEvent()

    data class AuthFailure(
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
