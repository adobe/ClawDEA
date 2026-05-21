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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates in-flight permission prompts.
 *
 * [submit] is called from the MCP handler thread; it creates a [PermissionRequest],
 * invokes [onRender] synchronously so the UI can emit a card, then waits on the
 * request's latch up to [DEFAULT_PROMPT_TIMEOUT_MS] for the UI to call [resolve].
 *
 * The timeout is a workaround for a Claude Code regression (issue
 * anthropics/claude-code#50289, still open as of 2026-05): since CC v2.1.113 the
 * native binary ignores the per-server `timeout` field on HTTP MCP servers and
 * hard-stops every tool call at ~60s. If we keep the HTTP request open longer
 * than that, the CLI synthesises a tool failure on its side and the model
 * helpfully "tries something else" — exactly what the user does NOT want for a
 * pending permission prompt. We respond before the cliff (default 45s), and if
 * the user resolves later we cache the decision so the next retry of the same
 * (toolName, inputJson) skips the prompt entirely.
 *
 * Auto-allow notifications (e.g. "Allow all" silent-allow) flow through
 * [com.adobe.clawdea.chat.permission.AutoAllowSignal] instead — they are
 * panel-routed via the matching `ToolUse` event so multi-tab projects don't
 * render the marker in the wrong tab.
 */
open class PermissionDispatcher(
    private val onRender: (PermissionRequest) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val inFlight = ConcurrentHashMap<String, PermissionRequest>()
    private val abandoned = ConcurrentHashMap<String, String>() // requestId -> cacheKey
    private val pendingDecisions = ConcurrentHashMap<String, PendingDecision>() // cacheKey -> late decision
    private val counter = AtomicLong(0)

    /**
     * Outcome of [submit]: the user's decision plus an optional updated input
     * payload. AskUserQuestion uses [updatedInput] to fold collected answers
     * back into the tool's input before the CLI runs the tool. [timedOut]
     * indicates the submit returned because the prompt exceeded the timeout
     * budget (the UI prompt remains open and a future call with the same
     * (toolName, inputJson) will consume the eventual decision).
     */
    data class Result(
        val decision: PermissionRequest.Decision,
        val updatedInput: String? = null,
        val timedOut: Boolean = false,
    )

    private data class PendingDecision(
        val decision: PermissionRequest.Decision,
        val updatedInput: String?,
        val createdAt: Long,
    )

    open fun submit(
        toolName: String,
        inputJson: String,
        timeoutMs: Long = DEFAULT_PROMPT_TIMEOUT_MS,
        toolUseId: String? = null,
    ): Result {
        val cacheKey = cacheKey(toolName, inputJson)
        consumePendingDecision(cacheKey)?.let { return it }

        val request = newRequest(toolName, inputJson, toolUseId)
        inFlight[request.requestId] = request
        try {
            onRender(request)
        } catch (e: Exception) {
            // UI failed to render: deny safely so the CLI does not stall.
            inFlight.remove(request.requestId)
            return Result(PermissionRequest.Decision.DENY)
        }
        val responded = try {
            request.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            inFlight.remove(request.requestId)
            return Result(PermissionRequest.Decision.DENY)
        }

        synchronized(lock) {
            // Either the latch fired (responded == true) or someone resolved
            // the request between the latch timeout and our acquiring the lock.
            // In both cases use the recorded decision and clean up.
            if (responded || request.decision != null) {
                val decided = request.decision ?: PermissionRequest.Decision.DENY
                val updated = request.updatedInput
                inFlight.remove(request.requestId)
                abandoned.remove(request.requestId)
                return Result(decided, updated)
            }

            // Timed out, no decision yet. Mark the request as abandoned by the
            // submit thread so a subsequent resolve() caches the decision for
            // the next attempt instead of dropping it on the floor.
            abandoned[request.requestId] = cacheKey
        }
        return Result(PermissionRequest.Decision.DENY, timedOut = true)
    }

    /**
     * Returns `true` when this resolution arrived *after* [submit] already
     * timed out and returned a synthetic deny to CC — i.e. the original tool
     * call has already been finalised on the CLI side, so the only way the
     * decision can reach Claude is for the caller to inject it as a separate
     * user message on the next turn (see [PermissionRequestHandler]'s
     * `onLateAnswer` path). The cache fallback under (toolName, inputJson)
     * is still populated for the same decision so a coincident retry by the
     * model is also a no-op for the user.
     *
     * Returns `false` for the normal in-time path (submit still on the latch),
     * for an unknown / already-resolved request id, and for the duplicate
     * resolve no-op.
     */
    fun resolve(
        requestId: String,
        decision: PermissionRequest.Decision,
        updatedInput: String? = null,
    ): Boolean {
        synchronized(lock) {
            val request = inFlight[requestId] ?: return false
            val first = request.resolve(decision, updatedInput)
            if (!first) return false

            val cacheKey = abandoned.remove(requestId)
            if (cacheKey != null) {
                // submit() has already given up on this request and returned
                // a timed-out deny to CC. Park the late decision under the
                // (tool, input) key so the next retry consumes it instantly.
                pruneExpiredDecisions()
                pendingDecisions[cacheKey] = PendingDecision(decision, updatedInput, clock())
                inFlight.remove(requestId)
                return true
            }
            // If cacheKey is null, submit() is still on the latch; it will
            // discover the decision when it wakes up and clean inFlight itself.
            return false
        }
    }

    fun hasInFlightRequests(): Boolean =
        inFlight.isNotEmpty()

    private fun consumePendingDecision(cacheKey: String): Result? {
        pruneExpiredDecisions()
        val pending = pendingDecisions.remove(cacheKey) ?: return null
        if (clock() - pending.createdAt > PENDING_DECISION_TTL_MS) return null
        return Result(pending.decision, pending.updatedInput, timedOut = false)
    }

    private fun pruneExpiredDecisions() {
        val now = clock()
        val cutoff = now - PENDING_DECISION_TTL_MS
        val it = pendingDecisions.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.createdAt < cutoff) it.remove()
        }
    }

    private fun cacheKey(toolName: String, inputJson: String): String =
        toolName + "\u0000" + inputJson

    private fun newRequest(toolName: String, inputJson: String, toolUseId: String?): PermissionRequest {
        val requestId = "perm-${counter.incrementAndGet()}"
        val summary = PermissionSummaryBuilder.build(toolName, inputJson)
        return PermissionRequest(requestId, toolName, inputJson, summary, toolUseId)
    }

    /** Visible for testing. */
    internal fun peek(requestId: String): PermissionRequest? = inFlight[requestId]

    /** Visible for testing. */
    internal fun pendingDecisionCount(): Int = pendingDecisions.size

    companion object {
        /**
         * Maximum time a single submit() blocks before returning a timed-out
         * deny. Must stay safely under Claude Code's hard ~60 s HTTP MCP cap
         * (issue #50289) so the CLI never sees the request as failed.
         */
        const val DEFAULT_PROMPT_TIMEOUT_MS: Long = 45_000

        /** TTL for cached late decisions used by the next retry. */
        const val PENDING_DECISION_TTL_MS: Long = 5 * 60_000
    }
}
