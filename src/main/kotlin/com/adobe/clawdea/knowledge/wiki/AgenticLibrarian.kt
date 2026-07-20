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
package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.provider.openai.agent.AgentClient
import com.adobe.clawdea.provider.openai.agent.AgentLoopController
import com.adobe.clawdea.provider.openai.agent.AgentMessage
import com.adobe.clawdea.provider.openai.agent.AgentToolExecutor
import com.adobe.clawdea.provider.openai.agent.ConversationState
import com.adobe.clawdea.provider.openai.agent.OpenAiToolDefinition
import com.adobe.clawdea.provider.openai.catalog.ModelCapability

/** The librarian's synthesized answer (or an actionable error message). */
data class LibrarianAnswer(val text: String, val isError: Boolean)

/**
 * Runs ONE wiki-librarian question through the agentic tool loop on the WIKI provider and returns
 * the model's final text. Symmetric to [com.adobe.clawdea.knowledge.drift.LoopBackedWikiSession],
 * but returns the answer text instead of Result<Unit> (the librarian answers; it does not author).
 *
 * Capability guard: a non-AGENTIC model cannot call tools (read_wiki_page/find_symbol), so it can
 * never do the librarian's job. Refuse with an actionable error instead of a tool-less run.
 */
class AgenticLibrarian(
    private val client: AgentClient,
    private val executor: AgentToolExecutor,
    private val tools: List<OpenAiToolDefinition>,
    private val modelId: String,
    private val systemPrompt: String?,
    private val capability: ModelCapability,
    private val streaming: Boolean,
    private val maxToolRounds: Int = 20,
    private val maxElapsedMs: Long = 300_000,
    private val maxContextChars: Int = 1_000_000,
) {
    suspend fun ask(question: String): LibrarianAnswer {
        if (capability != ModelCapability.AGENTIC) {
            return LibrarianAnswer(
                "WIKI model '$modelId' is not tool-capable; assign an agentic model in " +
                    "Settings > Roles.",
                isError = true,
            )
        }
        val state = ConversationState()
        if (!systemPrompt.isNullOrBlank()) {
            state.messages.add(AgentMessage(role = "system", content = systemPrompt))
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
        )
        var terminalError: String? = null
        val turn = loop.runTurn(question, appendUserMessage = true) { event ->
            if (event is CliEvent.Result && event.isError) terminalError = event.text
        }
        return when {
            terminalError != null -> LibrarianAnswer(terminalError, isError = true)
            turn.streamFailed -> LibrarianAnswer(turn.finalText.ifBlank { "request failed" }, isError = true)
            turn.isError -> LibrarianAnswer(turn.finalText.ifBlank { "librarian turn error" }, isError = true)
            else -> LibrarianAnswer(turn.finalText, isError = false)
        }
    }
}
