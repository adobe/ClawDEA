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
package com.adobe.clawdea.mcp

/**
 * Builds the JSON payload that tells a Claude Code CLI client how to reach this
 * project's local McpServer. Consumed by both the streaming chat-panel session
 * (CliProcess) and the interactive `/cc` terminal (InteractiveCommandDialog),
 * each of which writes it to its own temp file and passes it via --mcp-config.
 *
 * `alwaysLoad: true` opts the server out of Claude Code's tool-search deferral
 * (requires CLI v2.1.121+). `timeout` gives interactive approval/review flows
 * enough time for a human decision. Older clients ignore unknown fields.
 */
internal const val MCP_CLIENT_INTERACTIVE_TIMEOUT_MS = 600_000

internal fun buildMcpClientConfigJson(port: Int): String =
    """{"mcpServers":{"clawdea-intellij":{"type":"http","url":"http://127.0.0.1:$port/mcp","alwaysLoad":true,"timeout":$MCP_CLIENT_INTERACTIVE_TIMEOUT_MS}}}"""

/**
 * The MCP server name codex uses to namespace ClawDEA's tools (`mcp__<name>__<tool>`).
 * Deliberately hyphen-free: codex `-c` overrides parse the key as a dotted TOML path, and a
 * hyphenated bare segment (`clawdea-intellij`) is not a valid unquoted TOML key.
 */
internal const val CODEX_MCP_SERVER_NAME = "clawdea"

/**
 * Builds the `-c` config-override args that register ClawDEA's local [McpServer] with the
 * `codex` CLI as a **Streamable HTTP** MCP server. Verified in the Phase-2 spike: codex's `rmcp`
 * client completes the full handshake against ClawDEA's HTTP server directly — no adapter — as
 * long as it is reachable at this URL (see 2026-07-14-codex-interface-findings.md → MCP transport).
 *
 * Passed as literal ProcessBuilder args (no shell), so the value keeps its embedded quotes:
 * `mcp_servers.clawdea.url="http://127.0.0.1:<port>/mcp"`.
 */
internal fun buildCodexMcpConfigArgs(port: Int): List<String> =
    listOf("-c", """mcp_servers.$CODEX_MCP_SERVER_NAME.url="http://127.0.0.1:$port/mcp"""")
