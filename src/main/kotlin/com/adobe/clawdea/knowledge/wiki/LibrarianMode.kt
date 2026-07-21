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

import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.provider.ProviderRegistry

/**
 * How the `ask_wiki_librarian` MCP tool executes the librarian, tiered by the WIKI role's provider
 * backend. Orthogonal to [LibrarianMode] (which tiers the Claude-chat `--agents` injection): this
 * decides the handler's runtime path regardless of which chat backend invoked the tool.
 * Mirrors [com.adobe.clawdea.knowledge.drift.DriftDetectionService.chooseWikiInvoker].
 */
enum class LibrarianExecution {
    /** Claude-family WIKI provider: run `claude -p` with a librarian-only `--agents` def, capture text. */
    CLAUDE_SUBPROCESS,
    /** OpenAI-compatible WIKI provider: run the in-process agentic tool loop (AgenticLibrarian). */
    AGENTIC_LOOP,
    /** Codex WIKI provider: run `codex exec --json` read-only over the on-disk wiki (CodexExecLibrarian). */
    CODEX_SUBPROCESS,
}

/** Pick the MCP tool's execution path for the WIKI role. Unknown providers resolve to Claude. */
fun chooseLibrarianExecution(selection: AgentSelection): LibrarianExecution =
    when (ProviderRegistry.require(selection.providerId).backendKind) {
        BackendKind.CLAUDE_CLI -> LibrarianExecution.CLAUDE_SUBPROCESS
        BackendKind.OPENAI_COMPATIBLE_HTTP -> LibrarianExecution.AGENTIC_LOOP
        BackendKind.CODEX_APP_SERVER -> LibrarianExecution.CODEX_SUBPROCESS
    }
