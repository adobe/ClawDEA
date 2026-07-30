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
package com.adobe.clawdea.settings

import com.adobe.clawdea.provider.BackendKind

/**
 * Single source of truth for the chat-mode segmented control (Auto / Plan / Ask) and its mapping
 * onto the Claude CLI's `--permission-mode`.
 *
 * Accepted `--permission-mode` values are `acceptEdits`, `auto`, `bypassPermissions`, `manual`,
 * `dontAsk`, `plan`. Only `Plan` maps to one:
 *  - `Auto` emits nothing, so the tool-approval mode's own mapping still applies
 *    (`allow-safe` → `--permission-mode auto`).
 *  - `Ask` has no CLI counterpart; the interactive `request_permission` prompt tool already
 *    provides ask-everything behavior, so it too emits nothing.
 *
 * Mirrors [ToolApprovalModeUi]'s shape so both chrome controls read the same way.
 */
object ChatModeUi {

    data class Mode(val key: String, val label: String)

    private val modes = listOf(
        Mode("auto", "Auto"),
        Mode("plan", "Plan"),
        Mode("ask", "Ask"),
    )

    val labels: List<String> = modes.map { it.label }

    const val DEFAULT_KEY: String = "auto"

    fun keyForLabel(label: String): String {
        val normalized = label.trim().lowercase()
        return modes.firstOrNull { it.key == normalized }?.key ?: DEFAULT_KEY
    }

    fun labelForKey(key: String): String {
        val normalized = key.trim().lowercase()
        return modes.firstOrNull { it.key == normalized }?.label ?: modes.first().label
    }

    /** The `--permission-mode` value for [chatMode], or null when the mode emits no flag. */
    fun permissionModeValue(chatMode: String): String? =
        if (keyForLabel(chatMode) == "plan") "plan" else null

    /** Only the Claude CLI accepts `--permission-mode`; the other backends ignore chat modes. */
    fun isSupported(backendKind: BackendKind): Boolean = backendKind == BackendKind.CLAUDE_CLI

    fun requiresCliRestart(oldKey: String, newKey: String): Boolean = oldKey != newKey
}
