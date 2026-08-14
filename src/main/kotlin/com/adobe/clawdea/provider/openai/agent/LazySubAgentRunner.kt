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

/**
 * A [SubAgentRunner] that defers building the real runner until the `Agent` tool is actually
 * dispatched.
 *
 * Building the concrete [SubAgentDispatcher] assembles the sub-agent system prompt, which runs
 * [com.adobe.clawdea.provider.openai.PrimerService]-backed instruction assembly (git subprocesses +
 * a REPO_STATE disk write). The `Agent` tool is usually never dispatched in a turn, and the
 * enclosing backend rebuilds its runner on every retry attempt — so eagerly building it made every
 * user turn (and every stream-failure retry) pay that cost for nothing.
 *
 * [toolName] is exposed cheaply (a constant), so the loop's per-tool-call name match
 * (`toolCall.name == runner.toolName`) never triggers a build. [provider] is invoked at most once,
 * on the first [run]; `by lazy` makes that thread-safe even though tool calls execute sequentially.
 */
class LazySubAgentRunner(
    override val toolName: String,
    provider: () -> SubAgentRunner,
) : SubAgentRunner {

    private val delegate: SubAgentRunner by lazy(provider)

    override suspend fun run(toolCall: AgentToolCall, emit: (CliEvent) -> Unit): ToolExecutionResult =
        delegate.run(toolCall, emit)
}
