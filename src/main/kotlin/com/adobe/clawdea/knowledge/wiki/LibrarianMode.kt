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
 * How the in-chat wiki-librarian is dispatched, tiered by the WIKI role's provider backend.
 * Mirrors [com.adobe.clawdea.knowledge.drift.DriftDetectionService.chooseWikiInvoker].
 */
enum class LibrarianMode {
    /** Claude-family WIKI provider: inject the librarian via `--agents`, honoring the WIKI model. */
    CLAUDE_SUBAGENT,
    /** OpenAI-compatible WIKI provider: expose `ask_wiki_librarian` MCP tool; no `--agents` librarian. */
    AGENTIC_MCP_TOOL,
    /** Codex WIKI provider (unsupported in-chat): fall back to the `--agents` subagent on the chat model. */
    CLAUDE_SUBAGENT_FALLBACK,
}

/** Pure routing decision for the WIKI role. Unknown providers resolve to Claude (ProviderRegistry default). */
fun chooseLibrarianMode(selection: AgentSelection): LibrarianMode =
    when (ProviderRegistry.require(selection.providerId).backendKind) {
        BackendKind.CLAUDE_CLI -> LibrarianMode.CLAUDE_SUBAGENT
        BackendKind.OPENAI_COMPATIBLE_HTTP -> LibrarianMode.AGENTIC_MCP_TOOL
        BackendKind.CODEX_APP_SERVER -> LibrarianMode.CLAUDE_SUBAGENT_FALLBACK
    }
