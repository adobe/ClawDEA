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
package com.adobe.clawdea.cli

import com.adobe.clawdea.chat.permission.ClaudePermissionSettingsReader
import com.adobe.clawdea.chat.permission.PermissionPolicy
import com.adobe.clawdea.chat.permission.PermissionRouterRegistry
import com.adobe.clawdea.mcp.McpServer
import com.adobe.clawdea.provider.openai.tools.MissingRouteBehavior
import com.adobe.clawdea.provider.openai.tools.SharedToolApprovalGate
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Decides whether a `codex app-server` approval request (a shell command or a file patch)
 * should be accepted, routing the decision through the *same* permission gate the Claude
 * backend uses ([PermissionRouterRegistry] → [com.adobe.clawdea.chat.permission.PermissionDispatcher]).
 *
 * This is Phase C of the app-server migration: it replaces the blanket auto-approve in
 * [CodexAppServerProcess] with ClawDEA's approval flow, so codex's own shell/patch calls honor
 * the "Tool approval" mode and "Auto-accept edits" setting exactly like Claude tool calls.
 *
 * ### Decision order (mirrors [com.adobe.clawdea.mcp.McpPermissionPromptTool])
 *  1. `allow-all` tool-approval mode → silent allow.
 *  2. Claude settings policy (`.claude/settings*.json` allow/deny rules) → allow / deny.
 *  3. Otherwise an interactive Allow/Deny card, blocking on the dispatcher until the user decides.
 *
 * File patches additionally short-circuit to allow when "Auto-accept edits" is on. (Codex is
 * steered toward ClawDEA's `propose_*` MCP tools, which already run the rich diff review, so a
 * *native* codex patch reaching this gate is the fallback path and is gated with a plain card.)
 *
 * ### Safety / non-regression
 * The card is routed into the tool block the parser rendered for the command/patch. When **no**
 * panel router claims the call — e.g. the approval arrived before the item's `item/started`
 * rendered, or no chat panel is open — [route] returns null and this gate **falls back to
 * approve** rather than deny. That keeps the codex backend no worse than the pre–Phase-C
 * auto-approve behavior (and the `workspace-write` sandbox still applies) instead of dead-ending
 * a command the user can't see a prompt for.
 *
 * ### Threading
 * [approveCommand]/[approveFileChange] block (the dispatcher waits for the user), so
 * [CodexAppServerProcess] must call them off its stdout reader thread — otherwise the reader
 * couldn't pump the very `item/started` notification that renders the tool block this card
 * attaches to.
 */
class CodexApprovalGate(
    toolApprovalMode: () -> String,
    private val autoAcceptEdits: () -> Boolean,
    policy: () -> PermissionPolicy?,
    route: (toolName: String, inputJson: String, toolUseId: String) -> PermissionRouterRegistry.Routed?,
    promptTimeoutMs: Long = CODEX_PROMPT_TIMEOUT_MS,
) {
    private val sharedGate = SharedToolApprovalGate(
        toolApprovalMode = toolApprovalMode,
        policy = policy,
        route = route,
        promptTimeoutMs = promptTimeoutMs,
    )

    /** @return true to accept the shell command, false to deny. */
    fun approveCommand(command: String, toolUseId: String): Boolean =
        sharedGate.approve(
            toolName = "Bash",
            inputJson = commandInput(command),
            toolUseId = toolUseId,
            autoAcceptEdit = false,
            missingRouteBehavior = MissingRouteBehavior.APPROVE_FOR_CODEX_COMPATIBILITY,
        )

    /** @return true to accept the file patch, false to deny. */
    fun approveFileChange(paths: List<String>, toolUseId: String): Boolean =
        sharedGate.approve(
            toolName = PATCH_TOOL_NAME,
            inputJson = patchInput(paths),
            toolUseId = toolUseId,
            autoAcceptEdit = autoAcceptEdits(),
            missingRouteBehavior = MissingRouteBehavior.APPROVE_FOR_CODEX_COMPATIBILITY,
        )

    companion object {
        const val PATCH_TOOL_NAME = "apply_patch"

        /**
         * How long a single approval card blocks before the gate gives up and denies. Unlike the
         * Claude HTTP MCP path (hard ~60s cap, #50289), the app-server waits indefinitely on our
         * reply, so we can give the user a generous window.
         */
        const val CODEX_PROMPT_TIMEOUT_MS: Long = 10 * 60_000

        /**
         * How long [PermissionRouterRegistry.route] waits for a panel to claim the call. Sized a
         * bit above the default so a `requestApproval` that lands just before its `item/started`
         * renders still finds the tool block (the reader thread keeps pumping meanwhile).
         */
        const val CODEX_ROUTE_WAIT_MS: Long = 5_000

        /** Builds the `{"command":"..."}` input, byte-identical to the parser's Bash tool input so
         *  content-based routing ([PermissionRouter.claim]) matches when no tool_use_id is present. */
        internal fun commandInput(command: String): String =
            JsonObject().apply { addProperty("command", command) }.toString()

        internal fun patchInput(paths: List<String>): String =
            JsonObject().apply { addProperty("file_path", paths.joinToString(", ")) }.toString()

        /** Wires a gate to the project's live settings + router (production default). */
        fun forProject(project: Project): CodexApprovalGate {
            val registry = PermissionRouterRegistry.getInstance(project)
            return CodexApprovalGate(
                toolApprovalMode = { McpServer.getInstance(project).activeToolApprovalMode },
                autoAcceptEdits = { McpServer.getInstance(project).activeAutoAcceptEdits },
                policy = {
                    project.basePath?.let { basePath ->
                        PermissionPolicy {
                            ClaudePermissionSettingsReader(projectBasePath = Path.of(basePath)).read()
                        }
                    }
                },
                route = { toolName, inputJson, toolUseId ->
                    registry.route(toolName, inputJson, toolUseId, waitMs = CODEX_ROUTE_WAIT_MS)
                },
            )
        }
    }
}
