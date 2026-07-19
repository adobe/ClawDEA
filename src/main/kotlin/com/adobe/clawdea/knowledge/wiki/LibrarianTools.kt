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

import com.adobe.clawdea.provider.openai.agent.OpenAiToolDefinition

/**
 * The read-only tool set the agentic wiki-librarian may call. Mirrors the `tools:` frontmatter of
 * `/agents/wiki-librarian.md`, but with BARE MCP names (McpToolRouter names carry no
 * `mcp__clawdea-intellij__` prefix). Edit/Write/shell/apply_patch are intentionally absent — the
 * librarian reads and reasons, it never mutates files.
 */
val LIBRARIAN_TOOL_NAMES: Set<String> = setOf(
    "Read",
    "read_wiki_page",
    "search_text",
    "find_files",
    "find_symbol",
    "find_usages",
    "find_callers",
    "resolve_symbol",
    "get_diagnostics",
    "record_wiki_suggestion",
)

/** Keep only allowlisted read-only tools; drop everything else (Edit/Write/shell/etc.). */
fun filterLibrarianTools(all: List<OpenAiToolDefinition>): List<OpenAiToolDefinition> =
    all.filter { it.function.name in LIBRARIAN_TOOL_NAMES }
