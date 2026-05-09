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
 * (requires CLI v2.1.121+). Older clients ignore the field.
 */
internal fun buildMcpClientConfigJson(port: Int): String =
    """{"mcpServers":{"clawdea-intellij":{"type":"http","url":"http://127.0.0.1:$port/mcp","alwaysLoad":true}}}"""
