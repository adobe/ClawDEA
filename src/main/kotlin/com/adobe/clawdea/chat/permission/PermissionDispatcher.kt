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
package com.adobe.clawdea.chat.permission

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates in-flight permission prompts.
 *
 * [submit] is called from the MCP handler thread; it creates a [PermissionRequest],
 * invokes [onRender] synchronously so the UI can emit a card, then blocks on the
 * request's latch until the UI calls [resolve] with a decision. If the handler
 * thread is interrupted (plugin shutdown, server timeout), the method returns
 * [PermissionRequest.Decision.DENY].
 *
 * [notifyAutoAllowed] is the non-blocking companion: used when the MCP handler
 * has already decided to allow a call (e.g. under "Allow all") and just wants
 * to notify the UI that a tool ran without approval. The UI renders a compact
 * "auto-allowed" notice rather than an interactive card.
 */
class PermissionDispatcher(
    private val onRender: (PermissionRequest) -> Unit,
    private val onAutoAllowed: (PermissionRequest) -> Unit = {},
) {
    private val inFlight = ConcurrentHashMap<String, PermissionRequest>()
    private val counter = AtomicLong(0)

    /**
     * Outcome of [submit]: the user's decision plus an optional updated input
     * payload. AskUserQuestion uses [updatedInput] to fold collected answers
     * back into the tool's input before the CLI runs the tool.
     */
    data class Result(
        val decision: PermissionRequest.Decision,
        val updatedInput: String? = null,
    )

    fun submit(toolName: String, inputJson: String): Result {
        val request = newRequest(toolName, inputJson)
        inFlight[request.requestId] = request
        try {
            onRender(request)
        } catch (e: Exception) {
            // UI failed to render: deny safely so the CLI does not stall.
            inFlight.remove(request.requestId)
            return Result(PermissionRequest.Decision.DENY)
        }
        try {
            request.latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            inFlight.remove(request.requestId)
            return Result(PermissionRequest.Decision.DENY)
        }
        val decided = request.decision ?: PermissionRequest.Decision.DENY
        val updated = request.updatedInput
        inFlight.remove(request.requestId)
        return Result(decided, updated)
    }

    /**
     * Non-blocking counterpart to [submit]. The decision is already ALLOW; we
     * just want the UI to show a compact notice. Never touches [inFlight]
     * because there's nothing to resolve later — the request is born finished.
     */
    fun notifyAutoAllowed(toolName: String, inputJson: String) {
        val request = newRequest(toolName, inputJson)
        request.resolve(PermissionRequest.Decision.ALLOW)
        try {
            onAutoAllowed(request)
        } catch (_: Exception) {
            // UI failed to render: silent. The CLI call already proceeded.
        }
    }

    fun resolve(
        requestId: String,
        decision: PermissionRequest.Decision,
        updatedInput: String? = null,
    ) {
        inFlight[requestId]?.resolve(decision, updatedInput)
    }

    private fun newRequest(toolName: String, inputJson: String): PermissionRequest {
        val requestId = "perm-${counter.incrementAndGet()}"
        val summary = PermissionSummaryBuilder.build(toolName, inputJson)
        return PermissionRequest(requestId, toolName, inputJson, summary)
    }

    /** Visible for testing. */
    internal fun peek(requestId: String): PermissionRequest? = inFlight[requestId]
}
