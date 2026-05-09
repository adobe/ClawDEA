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
package com.adobe.clawdea.auth

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class SubscriptionAuthProviderTest {
    @Test fun `isConfigured true when signed in`() { assertTrue(SubscriptionAuthProvider(true).isConfigured()) }
    @Test fun `isConfigured false when not signed in`() { assertFalse(SubscriptionAuthProvider(false).isConfigured()) }
    @Test fun `applyToEnvironment strips 3P vars`() {
        val env = mutableMapOf(
            "CLAUDE_CODE_USE_BEDROCK" to "1", "CLAUDE_CODE_USE_VERTEX" to "1",
            "AWS_BEARER_TOKEN_BEDROCK" to "tok", "ANTHROPIC_API_KEY" to "sk-key",
            "UNRELATED" to "keep",
        )
        SubscriptionAuthProvider(true).applyToEnvironment(env)
        assertFalse(env.containsKey("CLAUDE_CODE_USE_BEDROCK"))
        assertFalse(env.containsKey("CLAUDE_CODE_USE_VERTEX"))
        assertFalse(env.containsKey("AWS_BEARER_TOKEN_BEDROCK"))
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"))
        assertEquals("keep", env["UNRELATED"])
    }
    @Test fun `validate succeeds when signed in`() { assertTrue(SubscriptionAuthProvider(true).validate().valid) }
    @Test fun `validate fails when not signed in`() {
        val r = SubscriptionAuthProvider(false).validate()
        assertFalse(r.valid)
        assertTrue(r.message!!.contains("subscription"))
    }
    @Test fun `testConnection fails when not signed in`() {
        val r = SubscriptionAuthProvider(false).testConnection()
        assertFalse(r.success)
        assertTrue(r.message.contains("Not signed in"))
    }
}
