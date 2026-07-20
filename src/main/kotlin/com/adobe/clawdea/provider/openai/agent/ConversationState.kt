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

/**
 * Mutable state for an OpenAI-compatible agent conversation.
 *
 * - [messages]: the full conversation history (user, assistant, tool results).
 * - [completedToolCallIds]: set of tool call IDs that have been executed (exactly-once guard).
 * - [partialAssistantText]: accumulator for assistant text deltas during streaming.
 * - [usage]: cumulative token usage across turns.
 */
data class ConversationState(
    val messages: MutableList<AgentMessage> = mutableListOf(),
    val completedToolCallIds: MutableSet<String> = mutableSetOf(),
    var partialAssistantText: String = "",
    var usage: AgentUsage = AgentUsage(),
)
