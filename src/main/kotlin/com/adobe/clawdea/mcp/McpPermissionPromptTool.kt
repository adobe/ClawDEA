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

/**
 * Implements the `request_permission` MCP tool used by the Claude CLI's
 * `--permission-prompt-tool` mechanism. Input:
 *   { "tool_name": string, "input": object-or-json-string }
 * Return body: a JSON string the CLI interprets:
 *   {"behavior":"allow","updatedInput":{...}}        -> proceed
 *   {"behavior":"deny","message":"..."}              -> block, feed message back to the model
 *
 * Decision order:
 *   1. Trusted tools (our own MCP + Read/Glob/Grep) — always allowed, no UI.
 *   2. Claude settings policy deny/allow — mirrors persisted Claude Code permissions.
 *   3. "allow-all" toolApprovalMode — silent-approved, UI shows a compact notice.
 *   4. Everything else — blocked on an interactive prompt card.
 */
class McpPermissionPromptTool(
    private val dispatcherSupplier: () -> PermissionDispatcher,
    private val toolApprovalModeSupplier: () -> String = { "confirm-all" },
    private val permissionPolicySupplier: () -> PermissionPolicy? = { null },
) {
    fun registerAll(router: McpToolRouter) {
        router.register(
            name = "request_permission",
            description = "Request user approval to run a tool. Invoked by the Claude CLI's --permission-prompt-tool.",
            properties = listOf(
                Triple("tool_name", "string", "Name of the tool the model wants to call"),
                Triple("input", "string", "JSON-encoded arguments the tool would receive"),
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

        if (isAutoAllowed(toolName)) {
            return McpToolRouter.ToolResult(buildAllowJson(inputJson))
        }

        policyResponse(toolName, inputJson)?.let {
            return it
        }

        // AskUserQuestion always requires the user to answer — auto-allow would
        // pass through with empty answers and the CLI tool returns a useless
        // "answer came through empty" result (see issue #141).
        if (shouldSilentlyAllow(toolName)) {
            dispatcherSupplier().notifyAutoAllowed(toolName, inputJson)
            return McpToolRouter.ToolResult(buildAllowJson(inputJson))
        }

        val result = dispatcherSupplier().submit(toolName, inputJson)
        return McpToolRouter.ToolResult(
            when (result.decision) {
                PermissionRequest.Decision.ALLOW -> buildAllowJson(result.updatedInput ?: inputJson)
                PermissionRequest.Decision.DENY -> buildDenyJson("Denied by user")
            },
        )
    }

    private fun policyResponse(toolName: String, inputJson: String): McpToolRouter.ToolResult? =
        when (permissionPolicySupplier()?.evaluate(toolName, inputJson)?.decision) {
            PermissionPolicy.Decision.DENY -> McpToolRouter.ToolResult(buildDenyJson("Denied by Claude settings"))
            PermissionPolicy.Decision.ALLOW -> McpToolRouter.ToolResult(buildAllowJson(inputJson))
            PermissionPolicy.Decision.ASK, null -> null
        }

    private fun shouldSilentlyAllow(toolName: String): Boolean =
        toolApprovalModeSupplier() == "allow-all" && toolName != ASK_USER_QUESTION

    companion object {
        const val ASK_USER_QUESTION = "AskUserQuestion"

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
    }
}
