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
package com.adobe.clawdea.mcp.coexistence

/**
 * The exact ClawDEA tool names that must NOT be registered when the JetBrains
 * MCP server is detected as enabled. Each entry has a same-named or near-same
 * counterpart on the JetBrains side and is dropped to avoid CLI ambiguity.
 *
 * Justification per name:
 * - `search_text`     — JB has the same name with the same semantics.
 * - `find_files`      — JB covers via `find_files_by_glob` + `find_files_by_name_keyword`.
 * - `resolve_symbol`  — JB's `get_symbol_info` (Quick-Doc-style) is richer.
 * - `get_diagnostics` — JB's `get_file_problems` + `build_project` cover the surface.
 *
 * Any change to this set must update [CollidingToolNamesTest].
 */
val CollidingToolNames: Set<String> = setOf(
    "search_text",
    "find_files",
    "resolve_symbol",
    "get_diagnostics",
)
