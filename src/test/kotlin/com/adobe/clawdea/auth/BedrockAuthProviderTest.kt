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

class BedrockAuthProviderTest {
    @Test fun `isConfigured true when region set`() { assertTrue(BedrockAuthProvider("us-east-1", "").isConfigured()) }
    @Test fun `isConfigured false when both empty`() { assertFalse(BedrockAuthProvider("", "").isConfigured()) }
    @Test fun `applyToEnvironment sets vars`() {
        val env = mutableMapOf<String, String>()
        BedrockAuthProvider("us-west-2", "tok").applyToEnvironment(env)
        assertEquals("1", env["CLAUDE_CODE_USE_BEDROCK"])
        assertEquals("us-west-2", env["AWS_REGION"])
        assertEquals("tok", env["AWS_BEARER_TOKEN_BEDROCK"])
    }
    @Test fun `applyToEnvironment skips blanks`() {
        val env = mutableMapOf<String, String>()
        BedrockAuthProvider("", "").applyToEnvironment(env)
        assertEquals("1", env["CLAUDE_CODE_USE_BEDROCK"])
        assertFalse(env.containsKey("AWS_REGION"))
    }
    @Test fun `validate fails when empty`() { assertFalse(BedrockAuthProvider("", "").validate().valid) }
    @Test fun `validate succeeds when configured`() { assertTrue(BedrockAuthProvider("us-east-1", "").validate().valid) }
    @Test fun `testConnection fails when not configured`() {
        val r = BedrockAuthProvider("", "").testConnection()
        assertFalse(r.success)
        assertTrue(r.message.contains("not configured"))
    }
    @Test fun `resolvedRegion falls back to env`() {
        val p = BedrockAuthProvider(region = { "" }, bearerToken = { "" }, envRegion = { "us-west-2" }, envBearerToken = { null })
        assertEquals("us-west-2", p.resolvedRegion())
    }
    @Test fun `resolvedBearerToken falls back to env`() {
        val p = BedrockAuthProvider(region = { "" }, bearerToken = { "" }, envRegion = { null }, envBearerToken = { "env-tok" })
        assertEquals("env-tok", p.resolvedBearerToken())
    }
    @Test fun `resolvedRegion prefers settings over env`() {
        val p = BedrockAuthProvider(region = { "settings-region" }, bearerToken = { "" }, envRegion = { "env-region" }, envBearerToken = { null })
        assertEquals("settings-region", p.resolvedRegion())
    }
    @Test fun `isConfigured true when only env vars set`() {
        val p = BedrockAuthProvider(region = { "" }, bearerToken = { "" }, envRegion = { "us-east-1" }, envBearerToken = { null })
        assertTrue(p.isConfigured())
    }
}
