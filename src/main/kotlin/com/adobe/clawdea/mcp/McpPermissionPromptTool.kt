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

import com.adobe.clawdea.chat.permission.PermissionDispatcher
import com.adobe.clawdea.chat.permission.PermissionPolicy
import com.adobe.clawdea.chat.permission.PermissionRequest
import com.adobe.clawdea.chat.permission.PermissionRouterRegistry
import com.adobe.clawdea.chat.permission.PermissionToolInput
import com.intellij.openapi.diagnostic.Logger

/**
 * Notified when a tool call is auto-allowed by "Allow all" mode (or trusted-MCP)
 * so the matching ChatPanel can flag the tool block as auto-allowed once its
 * `ToolUse` event arrives. See [com.adobe.clawdea.chat.permission.AutoAllowSignal].
 *
 * `toolUseId` is the CLI-assigned id Claude Code passes alongside the tool call
 * (see qpK in claude-code 2.1.x — `H.call({tool_name, input, tool_use_id}, ...)`).
 * It is preferred for routing since `(toolName, inputJson)` is byte-fragile:
 * stream-json and JSON-RPC arguments may serialize the same input with subtly
 * different whitespace or escaping. Empty string when CC did not provide it
 * (older versions, or the stdio SDK path).
 */
fun interface AutoAllowNotifier {
    fun notify(toolName: String, inputJson: String, toolUseId: String)
}

/**
 * Implements the `request_permission` MCP tool used by the Claude CLI's
 * `--permission-prompt-tool` mechanism. Input:
 *   { "tool_name": string, "input": object-or-json-string }
 * Return body: a JSON string the CLI interprets:
 *   {"behavior":"allow","updatedInput":{...}}        -> proceed
 *   {"behavior":"deny","message":"..."}              -> block, feed message back to the model
 *
 * Decision order:
 *   1. Claude settings policy DENY — the user's explicit veto, above everything else.
 *   2. Trusted tools (our own MCP + Read/Glob/Grep) — allowed, no UI.
 *   3. Claude settings policy ALLOW — mirrors persisted Claude Code permissions.
 *   4. "allow-all" toolApprovalMode — silent-approved, UI shows a compact notice.
 *   5. Everything else — blocked on an interactive prompt card.
 */
class McpPermissionPromptTool(
    private val dispatcherResolver: (toolName: String, inputJson: String, toolUseId: String) -> PermissionRouterRegistry.Routed?,
    private val toolApprovalModeSupplier: () -> String = { "confirm-all" },
    private val permissionPolicySupplier: () -> PermissionPolicy? = { null },
    private val autoAllowNotifier: AutoAllowNotifier = AutoAllowNotifier { _, _, _ -> },
) {
    private val log = Logger.getInstance(McpPermissionPromptTool::class.java)

    fun registerAll(router: McpToolRouter) {
        router.register(
            name = "request_permission",
            description = "Request user approval to run a tool. Invoked by the Claude CLI's --permission-prompt-tool.",
            properties = listOf(
                Triple("tool_name", "string", "Name of the tool the model wants to call"),
                Triple("input", "string", "JSON-encoded arguments the tool would receive"),
                Triple("tool_use_id", "string", "CLI-assigned id of the tool call (claude-code passes this alongside tool_name/input)"),
            ),
            required = listOf("tool_name"),
            handler = ::handle,
        )
    }

    /** Visible for testing. */
    internal fun handle(args: Map<String, String>): McpToolRouter.ToolResult {
        val toolName = args["tool_name"]
            ?: return McpToolRouter.ToolResult("Missing 'tool_name' argument", isError = true)
        val inputJson = args["input"].orEmpty()
        val toolUseId = args["tool_use_id"].orEmpty()

        // One policy evaluation, consulted at two rungs of the ladder (deny above the trusted
        // list, allow below it). See [resolveStage] for why the order is what it is.
        // PermissionPolicy.evaluate already degrades an unreadable settings file to ASK, so no
        // extra guard is needed here.
        val policyDecision = permissionPolicySupplier()?.evaluate(toolName, inputJson)?.decision

        when (resolveStage(toolName, policyDecision, toolApprovalModeSupplier())) {
            Stage.SETTINGS_DENY -> {
                logDecision("settings-deny", toolName, inputJson)
                return McpToolRouter.ToolResult(buildDenyJson("Denied by Claude settings"))
            }
            Stage.TRUSTED_ALLOW -> {
                logDecision("trusted-auto-allow", toolName, inputJson)
                return McpToolRouter.ToolResult(buildAllowJson(inputJson))
            }
            Stage.SETTINGS_ALLOW -> {
                logDecision("settings-allow", toolName, inputJson)
                return McpToolRouter.ToolResult(buildAllowJson(inputJson))
            }
            // AskUserQuestion always requires the user to answer — auto-allow would pass
            // through with empty answers and the CLI tool returns a useless "answer came
            // through empty" result (see issue #141).
            Stage.SILENT_ALLOW -> {
                logDecision("allow-all", toolName, inputJson)
                autoAllowNotifier.notify(toolName, inputJson, toolUseId)
                return McpToolRouter.ToolResult(buildAllowJson(inputJson))
            }
            Stage.PROMPT -> Unit // fall through to the dispatcher below
        }

        val routed = dispatcherResolver(toolName, inputJson, toolUseId)
        if (routed == null) {
            // No ChatPanel claims this call. This happens if the panel was
            // closed between the ToolUse event and the request_permission
            // round-trip, or if the routing entry was already consumed by
            // another path. Deny safely so the CLI doesn't stall waiting on
            // a UI that doesn't exist.
            logDecision("no-router", toolName, inputJson)
            return McpToolRouter.ToolResult(buildDenyJson("No active chat panel claimed this tool call"))
        }
        val result = routed.dispatcher.submit(toolName, inputJson, toolUseId = routed.toolUseId)
        if (result.timedOut) {
            logDecision("prompt-timeout", toolName, inputJson)
            return McpToolRouter.ToolResult(buildTimedOutJson())
        }
        logDecision("prompt-${result.decision.name.lowercase()}", toolName, inputJson)
        return McpToolRouter.ToolResult(
            when (result.decision) {
                PermissionRequest.Decision.ALLOW -> buildAllowJson(result.updatedInput ?: inputJson)
                PermissionRequest.Decision.DENY -> buildDenyJson("Denied by user")
            },
        )
    }

    private fun logDecision(decision: String, toolName: String, inputJson: String) {
        val specifier = PermissionToolInput.extractSpecifier(toolName, inputJson).orEmpty()
        log.info(
            "permission decision=$decision mode=${toolApprovalModeSupplier()} " +
                "tool=$toolName input=${specifier.take(160)}"
        )
    }

    /** Which rung of the decision ladder handles a request. See companion [resolveStage]. */
    internal enum class Stage { SETTINGS_DENY, TRUSTED_ALLOW, SETTINGS_ALLOW, SILENT_ALLOW, PROMPT }

    companion object {
        const val ASK_USER_QUESTION = "AskUserQuestion"

        /**
         * The decision ladder, as a pure function of the request.
         *
         * A user's explicit deny outranks everything — including the trusted list, which covers
         * Read/Glob/Grep and our own MCP tools. Those are read-only but not harmless: a
         * `deny: ["Read(**​/.env)"]` rule exists precisely to keep them out of secrets.
         */
        internal fun resolveStage(
            toolName: String,
            policyDecision: PermissionPolicy.Decision?,
            toolApprovalMode: String,
        ): Stage = when {
            policyDecision == PermissionPolicy.Decision.DENY -> Stage.SETTINGS_DENY
            isAutoAllowed(toolName) -> Stage.TRUSTED_ALLOW
            policyDecision == PermissionPolicy.Decision.ALLOW -> Stage.SETTINGS_ALLOW
            toolApprovalMode == "allow-all" && toolName != ASK_USER_QUESTION -> Stage.SILENT_ALLOW
            else -> Stage.PROMPT
        }

        fun buildAllowJson(inputJson: String): String {
            // The Claude Code --permission-prompt-tool contract requires updatedInput
            // on allow (the original input, unchanged, or a modified version). Passing
            // an empty object on missing input is safe for read-only tools that don't
            // inspect the field.
            val passthrough = inputJson.trim().ifEmpty { "{}" }
            return """{"behavior":"allow","updatedInput":$passthrough}"""
        }

        fun isAutoAllowed(toolName: String): Boolean {
            if (toolName.startsWith("mcp__clawdea-intellij__")) return true
            return toolName in setOf("Read", "Glob", "Grep")
        }

        fun buildDenyJson(message: String): String {
            val fullMessage = "$message. If the user later asks you to run it or changes tool approval settings, try the same tool call again so ClawDEA can prompt or auto-allow it under the current setting."
            val escaped = McpProtocol.escapeJsonString(fullMessage)
            return """{"behavior":"deny","message":"$escaped"}"""
        }

        /**
         * Returned when the user has not yet decided within the dispatcher's
         * timeout budget. The Claude Code CLI's HTTP MCP transport caps every
         * tool call at ~60 s (regression #50289), so we have to respond before
         * the cliff or the CLI fabricates a "tool timed out" failure and
         * encourages the model to try a different command. The deny message
         * is worded so Claude STOPS and waits instead of pivoting.
         */
        fun buildTimedOutJson(): String {
            val fullMessage = "The user is still reviewing this request in the IDE — Claude Code's HTTP MCP transport ended this request before the user responded (claude-code regression #50289). DO NOT try a different command, DO NOT retry on your own, and DO NOT report a tool failure to the user. Tell the user you are waiting for them to approve in the IDE; once they do, they will tell you to continue and the next attempt will run."
            val escaped = McpProtocol.escapeJsonString(fullMessage)
            return """{"behavior":"deny","message":"$escaped"}"""
        }
    }
}
