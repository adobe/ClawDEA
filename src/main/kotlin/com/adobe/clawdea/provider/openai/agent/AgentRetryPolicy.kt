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
package com.adobe.clawdea.provider.openai.agent

import kotlin.math.min
import kotlin.math.pow

/**
 * Context for retry decision.
 */
data class RetryContext(
    val status: Int?,
    val retryAfterSeconds: Long?,
    val emittedText: Boolean,
    val executedTools: Boolean,
    val authRenewals: Int,
    val attempts: Int,
)

/**
 * Retry decision sealed interface.
 */
sealed interface RetryDecision {
    data class RetryAfter(val delayMillis: Long) : RetryDecision
    data object RenewCredentialOnce : RetryDecision
    data object AskUser : RetryDecision
    data object Fail : RetryDecision
}

/**
 * Pure retry policy for agent HTTP errors.
 *
 * Rules (in precedence order):
 * 1. Partial output (emittedText or executedTools) → AskUser (NEVER auto-retry)
 * 2. 401/403 → RenewCredentialOnce (only if authRenewals == 0, else Fail)
 * 3. 429 → RetryAfter (capped at 60s, max 2 attempts)
 * 4. Connection error or 5xx → RetryAfter (exponential backoff, max 2 attempts, only if no output)
 * 5. Otherwise → Fail
 */
object AgentRetryPolicy {
    private const val DEFAULT_RETRY_AFTER_SECONDS = 5L
    private const val MAX_RETRY_AFTER_SECONDS = 60L
    private const val BASE_BACKOFF_MS = 2000L
    private const val MAX_RETRY_ATTEMPTS = 2

    fun decide(ctx: RetryContext): RetryDecision {
        // Rule 1: Partial output takes precedence
        if (ctx.emittedText || ctx.executedTools) {
            return RetryDecision.AskUser
        }

        // Rule 2: Auth errors (401/403)
        if (ctx.status in listOf(401, 403)) {
            return if (ctx.authRenewals == 0) {
                RetryDecision.RenewCredentialOnce
            } else {
                RetryDecision.Fail
            }
        }

        // Rule 3: Rate limiting (429)
        if (ctx.status == 429) {
            if (ctx.attempts >= MAX_RETRY_ATTEMPTS) {
                return RetryDecision.Fail
            }
            val delaySec = min(ctx.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS, MAX_RETRY_AFTER_SECONDS)
            return RetryDecision.RetryAfter(delaySec * 1000)
        }

        // Rule 4: Connection errors (status == null) or 5xx
        if (ctx.status == null || ctx.status in 500..599) {
            if (ctx.attempts >= MAX_RETRY_ATTEMPTS) {
                return RetryDecision.Fail
            }
            val backoffMs = BASE_BACKOFF_MS * (2.0.pow(ctx.attempts.toDouble())).toLong()
            return RetryDecision.RetryAfter(backoffMs)
        }

        // Rule 5: All other cases → Fail
        return RetryDecision.Fail
    }
}
