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
package com.adobe.clawdea.approval

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Hand-off between the MCP `request_permission` handler (which knows
 * `(toolName, inputJson)` but not the `tool_use_id`) and the per-panel
 * [com.adobe.clawdea.chat.EventStreamHandler] (which sees the matching
 * `ToolUse` event and owns the right ChatPanel's browser).
 *
 * Why this exists: a single project can host multiple ChatPanel tabs sharing
 * one [PermissionDispatcherHolder]. The dispatcher's `onAutoAllowed` callback
 * used to render a standalone "⚡ Auto-allowed: <tool>" card via `appendHtml`,
 * which targets whichever panel is currently focused — not the one whose CLI
 * session emitted the tool call. The card landed in the wrong tab.
 *
 * Routing now flows through ToolUse instead: the MCP handler [notify]s a
 * `(toolName, inputJson)` entry; each panel's EventStreamHandler [consume]s
 * the entry when its own `ToolUse` arrives, giving us the correct
 * `(panel, tool_use_id)` by construction.
 *
 * Entries are bounded ([MAX_PENDING]) and TTL-pruned ([ENTRY_TTL_MS]) so a
 * dropped or unconsumed signal never grows the queue forever.
 */
@Service(Service.Level.PROJECT)
class AutoAllowSignal(@Suppress("unused") private val project: Project? = null) {

    private val entries = ConcurrentHashMap<String, Long>()
    private val clock: () -> Long = System::currentTimeMillis

    /**
     * Record that a tool call was auto-allowed.
     *
     * Preferred path: `toolUseId` is the CLI-assigned id (byte-stable). When
     * present we key on it directly; the (toolName, inputJson) fallback is
     * used only when CC does not pass `tool_use_id` (older versions, stdio
     * SDK path) so signals are still routable.
     */
    fun notify(toolName: String, inputJson: String, toolUseId: String = "") {
        prune()
        if (entries.size >= MAX_PENDING) return
        val k = if (toolUseId.isNotEmpty()) idKey(toolUseId) else nameKey(toolName, inputJson)
        entries[k] = clock()
    }

    /** Consume by tool_use_id (preferred). Returns true if a matching signal existed. */
    fun consume(toolUseId: String): Boolean {
        if (toolUseId.isEmpty()) return false
        prune()
        return entries.remove(idKey(toolUseId)) != null
    }

    /** Fallback consume by (toolName, inputJson) when no id is available. */
    fun consume(toolName: String, inputJson: String): Boolean {
        prune()
        return entries.remove(nameKey(toolName, inputJson)) != null
    }

    internal fun pendingSize(): Int = entries.size

    private fun prune() {
        val cutoff = clock() - ENTRY_TTL_MS
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < cutoff) it.remove()
        }
    }

    private fun idKey(toolUseId: String): String = "id " + toolUseId

    private fun nameKey(toolName: String, inputJson: String): String =
        "ni " + toolName + " " + inputJson

    companion object {
        const val MAX_PENDING = 64
        const val ENTRY_TTL_MS: Long = 5 * 60_000

        fun getInstance(project: Project): AutoAllowSignal =
            project.getService(AutoAllowSignal::class.java)
    }
}
