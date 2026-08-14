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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One panel's claim to handle a permission request. Each ChatPanel registers
 * a router; the MCP server consults [PermissionRouterRegistry.route] to pick
 * the panel whose CliBridge actually emitted the matching `ToolUse`.
 *
 * Why this exists: a single project can host multiple ChatPanel tabs. The
 * old `PermissionDispatcherHolder` was winner-take-all (only the most recently
 * activated panel's dispatcher was visible to the MCP server), so a permission
 * card for a tool call from a backgrounded tab landed in whichever tab was
 * focused. This registry routes per-call by `(toolName, inputJson)` instead.
 */
interface PermissionRouter {
    /**
     * Preferred routing path: returns true if this panel emitted the matching
     * `ToolUse` with this id. The id is byte-stable (CLI-assigned UUID) so it
     * does not suffer from JSON-serialization drift between stream-json and
     * JSON-RPC arguments.
     */
    fun claimById(toolUseId: String): Boolean = false

    /**
     * Fallback routing path used only when [claimById] is unavailable
     * (older CC versions, or the stdio SDK path that omits `tool_use_id`).
     * Returns a non-null `tool_use_id` if this panel's recent ToolUse stream
     * matches the given `(toolName, inputJson)`. The first router that
     * returns non-null wins.
     */
    fun claim(toolName: String, inputJson: String): String?
}

@Service(Service.Level.PROJECT)
class PermissionRouterRegistry(@Suppress("unused") private val project: Project? = null) {

    private data class Entry(val router: PermissionRouter, val dispatcher: PermissionDispatcher)

    private val entries = CopyOnWriteArrayList<Entry>()

    fun register(router: PermissionRouter, dispatcher: PermissionDispatcher) {
        entries.add(Entry(router, dispatcher))
    }

    fun unregister(dispatcher: PermissionDispatcher) {
        entries.removeAll { it.dispatcher === dispatcher }
    }

    /**
     * Pick the dispatcher and tool_use_id for an incoming permission request.
     * Returns null when no panel claims the call — the MCP layer falls back
     * to a deny-without-prompt response so the CLI does not stall.
     *
     * The CLI emits the matching `ToolUse` over stream-json (stdout) at roughly
     * the same time it invokes `request_permission` over HTTP. Our stdout
     * pipeline hops Flow→`invokeLater`→EDT before the panel's claim-map is
     * populated, so the HTTP handler frequently arrives first and finds an
     * empty registry. We therefore poll briefly: if no router claims the call
     * immediately, wait up to [waitMs] for one to register before giving up.
     * The HTTP request is already blocked on the prompt timeout (~45 s), so
     * adding a small wait here costs nothing in the happy path and prevents
     * the spurious "No active chat panel claimed this tool call" deny.
     */
    fun route(
        toolName: String,
        inputJson: String,
        toolUseId: String = "",
        waitMs: Long = ROUTE_WAIT_MS,
    ): Routed? {
        val deadline = System.currentTimeMillis() + waitMs
        while (true) {
            for (entry in entries) {
                if (toolUseId.isNotEmpty()) {
                    val claimed = try {
                        entry.router.claimById(toolUseId)
                    } catch (_: Throwable) {
                        false
                    }
                    if (claimed) return Routed(entry.dispatcher, toolUseId)
                } else {
                    val claimedId = try {
                        entry.router.claim(toolName, inputJson)
                    } catch (_: Throwable) {
                        null
                    }
                    if (claimedId != null) return Routed(entry.dispatcher, claimedId)
                }
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            try {
                Thread.sleep(minOf(remaining, ROUTE_POLL_INTERVAL_MS))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    internal fun size(): Int = entries.size

    data class Routed(val dispatcher: PermissionDispatcher, val toolUseId: String)

    companion object {
        /**
         * Upper bound on how long [route] waits for a panel router to claim a
         * call. Sized so that even slow EDT pumps (long-running paint or large
         * tool render) almost certainly land inside the window, while staying
         * well under the 45 s prompt-timeout budget.
         */
        const val ROUTE_WAIT_MS: Long = 2_000

        /** Poll cadence while waiting for a router to register. */
        const val ROUTE_POLL_INTERVAL_MS: Long = 25

        fun getInstance(project: Project): PermissionRouterRegistry =
            project.getService(PermissionRouterRegistry::class.java)
    }
}
