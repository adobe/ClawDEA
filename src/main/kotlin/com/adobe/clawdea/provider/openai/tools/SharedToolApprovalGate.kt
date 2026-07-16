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
package com.adobe.clawdea.provider.openai.tools

import com.adobe.clawdea.chat.permission.PermissionPolicy
import com.adobe.clawdea.chat.permission.PermissionRequest
import com.adobe.clawdea.chat.permission.PermissionRouterRegistry
import com.intellij.openapi.diagnostic.Logger

/**
 * Behavior when no permission router claims a tool call (i.e., no chat panel
 * can display the approval card).
 */
enum class MissingRouteBehavior {
    /**
     * Deny the request. Used by the new HTTP agent backend where a command/patch
     * that can't be shown to the user must never execute.
     */
    DENY,

    /**
     * Auto-approve the request. Preserves Codex's pre–Phase-C behavior where
     * commands were auto-approved when no router claimed them, keeping the
     * system no worse than the prior auto-approve path (the sandbox still applies).
     */
    APPROVE_FOR_CODEX_COMPATIBILITY,
}

/**
 * Shared approval decision logic for tool calls (shell commands, file patches).
 * Reused by [com.adobe.clawdea.cli.CodexApprovalGate] and the new HTTP agent
 * backend. Extracts the decision order so both paths honor the same permission
 * settings, policies, and routing.
 *
 * ### Decision order
 * 1. [autoAcceptEdit] short-circuit: when true, immediately allow (the caller
 *    passes true only for patch/edit tools when auto-accept-edits is on).
 * 2. [toolApprovalMode] == "allow-all" → silent allow.
 * 3. [policy] DENY / ALLOW → deny / allow.
 * 4. [route] returns null → apply [missingRouteBehavior] (DENY → deny,
 *    APPROVE_FOR_CODEX_COMPATIBILITY → auto-approve + log).
 * 5. Otherwise dispatcher.submit → allow if ALLOW && !timedOut, else deny.
 *
 * A timeout always denies.
 *
 * ### Threading
 * [approve] blocks when routing to a dispatcher, so it must be called off-EDT.
 */
class SharedToolApprovalGate(
    private val toolApprovalMode: () -> String,
    private val policy: () -> PermissionPolicy?,
    private val route: (toolName: String, inputJson: String, toolUseId: String) -> PermissionRouterRegistry.Routed?,
    private val promptTimeoutMs: Long,
) {
    private val log = Logger.getInstance(SharedToolApprovalGate::class.java)

    fun approve(
        toolName: String,
        inputJson: String,
        toolUseId: String,
        autoAcceptEdit: Boolean,
        missingRouteBehavior: MissingRouteBehavior,
    ): Boolean {
        // 1. Auto-accept-edit short-circuit (caller passes true only for patch/edit tools)
        if (autoAcceptEdit) return true

        // 2. Allow-all mode
        if (toolApprovalMode() == "allow-all") return true

        // 3. Policy evaluation
        when (policy()?.evaluate(toolName, inputJson)?.decision) {
            PermissionPolicy.Decision.DENY -> return false
            PermissionPolicy.Decision.ALLOW -> return true
            PermissionPolicy.Decision.ASK, null -> Unit
        }

        // 4. Route to dispatcher
        val routed = route(toolName, inputJson, toolUseId)
        if (routed == null) {
            return when (missingRouteBehavior) {
                MissingRouteBehavior.DENY -> false
                MissingRouteBehavior.APPROVE_FOR_CODEX_COMPATIBILITY -> {
                    log.info("approval: no router claimed $toolName; auto-approving (CODEX_COMPAT)")
                    true
                }
            }
        }

        val result = routed.dispatcher.submit(
            toolName = toolName,
            inputJson = inputJson,
            timeoutMs = promptTimeoutMs,
            toolUseId = routed.toolUseId,
        )

        // Timeout always denies
        return result.decision == PermissionRequest.Decision.ALLOW && !result.timedOut
    }
}
