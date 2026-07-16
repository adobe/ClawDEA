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
 * Assembles streamed tool call fragments into complete tool calls.
 * The first fragment for an index carries id and name; continuation fragments
 * (same index, id/name null) append their arguments.
 */
class ToolCallAssembler {
    private data class PartialCall(
        val id: String,
        val name: String,
        val argumentsBuilder: StringBuilder,
    )

    private val calls = mutableMapOf<Int, PartialCall>()

    /**
     * Accept a tool fragment. The first fragment for an index must carry id and name.
     * Continuation fragments append arguments to the existing partial call.
     */
    fun accept(fragment: AgentStreamEvent.ToolFragment) {
        val existing = calls[fragment.index]
        if (existing == null) {
            // First fragment for this index - must have id and name
            val id = fragment.id ?: error("First fragment for index ${fragment.index} missing id")
            val name = fragment.name ?: error("First fragment for index ${fragment.index} missing name")
            calls[fragment.index] = PartialCall(id, name, StringBuilder(fragment.arguments))
        } else {
            // Continuation fragment - append arguments
            existing.argumentsBuilder.append(fragment.arguments)
        }
    }

    /**
     * Return completed tool calls in index order.
     */
    fun completed(): List<AgentToolCall> {
        return calls.entries
            .sortedBy { it.key }
            .map { (_, partial) ->
                AgentToolCall(partial.id, partial.name, partial.argumentsBuilder.toString())
            }
    }
}
