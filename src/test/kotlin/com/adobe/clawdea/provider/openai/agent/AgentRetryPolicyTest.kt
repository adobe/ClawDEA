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

import org.junit.Assert.*
import org.junit.Test

class AgentRetryPolicyTest {

    @Test
    fun `partial output takes precedence over auth error`() {
        val ctx = RetryContext(
            status = 401,
            retryAfterSeconds = null,
            emittedText = true,
            executedTools = false,
            authRenewals = 0,
            attempts = 0,
        )
        assertEquals(RetryDecision.AskUser, AgentRetryPolicy.decide(ctx))
    }

    @Test
    fun `executed tools takes precedence over auth error`() {
        val ctx = RetryContext(
            status = 401,
            retryAfterSeconds = null,
            emittedText = false,
            executedTools = true,
            authRenewals = 0,
            attempts = 0,
        )
        assertEquals(RetryDecision.AskUser, AgentRetryPolicy.decide(ctx))
    }

    @Test
    fun `401 with no output and no renewals returns RenewCredentialOnce`() {
        val ctx = RetryContext(
            status = 401,
            retryAfterSeconds = null,
            emittedText = false,
            executedTools = false,
            authRenewals = 0,
            attempts = 1,
        )
        assertEquals(RetryDecision.RenewCredentialOnce, AgentRetryPolicy.decide(ctx))
    }

    @Test
    fun `403 with no output and no renewals returns RenewCredentialOnce`() {
        val ctx = RetryContext(
            status = 403,
            retryAfterSeconds = null,
            emittedText = false,
            executedTools = false,
            authRenewals = 0,
            attempts = 1,
        )
        assertEquals(RetryDecision.RenewCredentialOnce, AgentRetryPolicy.decide(ctx))
    }

    @Test
    fun `401 after one renewal returns Fail`() {
        val ctx = RetryContext(
            status = 401,
            retryAfterSeconds = null,
            emittedText = false,
            executedTools = false,
            authRenewals = 1,
            attempts = 2,
        )
        assertEquals(RetryDecision.Fail, AgentRetryPolicy.decide(ctx))
    }

    @Test
    fun `429 with no output and attempt 0 returns RetryAfter`() {
        val ctx = RetryContext(
            status = 429,
            retryAfterSeconds = 10,
            emittedText = false,
            executedTools = false,
            authRenewals = 0,
            attempts = 0,
        )
        val decision = AgentRetryPolicy.decide(ctx)
        assertTrue(decision is RetryDecision.RetryAfter)
        assertEquals(10_000L, (decision as RetryDecision.RetryAfter).delayMillis)
    }

    @Test
    fun `429 with no Retry-After header uses default 5s`() {
        val ctx = RetryContext(
            status = 429,
            retryAfterSeconds = null,
            emittedText = false,
            executedTools = false,
            authRenewals = 0,
            attempts = 0,
        )
        val decision = AgentRetryPolicy.decide(ctx)
        assertTrue(decision is RetryDecision.RetryAfter)
        assertEquals(5_000L, (decision as RetryDecision.RetryAfter).delayMillis)
    }

    @Test
    fun `429 with Retry-After over 60s is capped at 60s`() {
        val ctx = RetryContext(
            status = 429,
            retryAfterSeconds = 120,
            emittedText = false,
            executedTools = false,
            authRenewals = 0,
            attempts = 0,
        )
        val decision = AgentRetryPolicy.decide(ctx)
        assertTrue(decision is RetryDecision.RetryAfter)
        assertEquals(60_000L, (decision as RetryDecision.RetryAfter).delayMillis)
    }

    @Test
    fun `429 on second attempt returns Fail`() {
        val ctx = RetryContext(
            status = 429,
            retryAfterSeconds = 10,
            emittedText = false,
            executedTools = false,
            authRenewals = 0,
            attempts = 2,
        )
        assertEquals(RetryDecision.Fail, AgentRetryPolicy.decide(ctx))
    }

    @Test
    fun `5xx with no output and attempt 0 returns RetryAfter with backoff`() {
        val ctx = RetryContext(
            status = 500,
            retryAfterSeconds = null,
            emittedText = false,
            executedTools = false,
            authRenewals = 0,
            attempts = 0,
        )
        val decision = AgentRetryPolicy.decide(ctx)
        assertTrue(decision is RetryDecision.RetryAfter)
        assertEquals(2_000L, (decision as RetryDecision.RetryAfter).delayMillis)
    }

    @Test
    fun `5xx on attempt 1 returns RetryAfter with doubled backoff`() {
        val ctx = RetryContext(
            status = 503,
            retryAfterSeconds = null,
            emittedText = false,
            executedTools = false,
            authRenewals = 0,
            attempts = 1,
        )
        val decision = AgentRetryPolicy.decide(ctx)
        assertTrue(decision is RetryDecision.RetryAfter)
        assertEquals(4_000L, (decision as RetryDecision.RetryAfter).delayMillis)
    }

    @Test
    fun `5xx on attempt 2 returns Fail`() {
        val ctx = RetryContext(
            status = 500,
            retryAfterSeconds = null,
            emittedText = false,
            executedTools = false,
            authRenewals = 0,
            attempts = 2,
        )
        assertEquals(RetryDecision.Fail, AgentRetryPolicy.decide(ctx))
    }

    @Test
    fun `connection error with no output and attempt 0 returns RetryAfter`() {
        val ctx = RetryContext(
            status = null,
            retryAfterSeconds = null,
            emittedText = false,
            executedTools = false,
            authRenewals = 0,
            attempts = 0,
        )
        val decision = AgentRetryPolicy.decide(ctx)
        assertTrue(decision is RetryDecision.RetryAfter)
        assertEquals(2_000L, (decision as RetryDecision.RetryAfter).delayMillis)
    }

    @Test
    fun `connection error after two attempts returns Fail`() {
        val ctx = RetryContext(
            status = null,
            retryAfterSeconds = null,
            emittedText = false,
            executedTools = false,
            authRenewals = 0,
            attempts = 2,
        )
        assertEquals(RetryDecision.Fail, AgentRetryPolicy.decide(ctx))
    }

    @Test
    fun `4xx error other than 401 403 429 returns Fail`() {
        val ctx = RetryContext(
            status = 400,
            retryAfterSeconds = null,
            emittedText = false,
            executedTools = false,
            authRenewals = 0,
            attempts = 0,
        )
        assertEquals(RetryDecision.Fail, AgentRetryPolicy.decide(ctx))
    }

    @Test
    fun `2xx success returns Fail`() {
        val ctx = RetryContext(
            status = 200,
            retryAfterSeconds = null,
            emittedText = false,
            executedTools = false,
            authRenewals = 0,
            attempts = 0,
        )
        assertEquals(RetryDecision.Fail, AgentRetryPolicy.decide(ctx))
    }
}
