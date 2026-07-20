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

import com.adobe.clawdea.provider.ProviderRegistry
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuthManagerTest {

    private fun createManager(
        activeProviderId: String = "anthropic",
        providers: Map<String, AuthProvider> = mapOf(
            "anthropic" to AnthropicAuthProvider(apiKey = "sk-test", envApiKey = null),
            "bedrock" to BedrockAuthProvider(region = "us-east-1", bearerToken = "tok"),
            "vertex" to VertexAuthProvider(region = "us-central1", projectId = "proj"),
            "subscription" to SubscriptionAuthProvider(isSignedIn = true),
        ),
    ): AuthManager = AuthManager(providers) { activeProviderId }

    @Test fun `activeProvider returns anthropic by default`() {
        assertEquals("anthropic", createManager("anthropic").activeProvider().id)
    }
    @Test fun `activeProvider returns bedrock when selected`() {
        assertEquals("bedrock", createManager("bedrock").activeProvider().id)
    }
    @Test fun `activeProvider falls back to anthropic for unknown id`() {
        assertEquals("anthropic", createManager("unknown").activeProvider().id)
    }
    @Test fun `applyToEnvironment delegates to active provider`() {
        val env = mutableMapOf<String, String>()
        createManager("bedrock").applyToEnvironment(env)
        assertEquals("1", env["CLAUDE_CODE_USE_BEDROCK"])
    }
    @Test fun `preflight returns valid when provider is configured`() {
        assertTrue(createManager("anthropic").preflight().valid)
    }
    @Test fun `preflight returns invalid when provider not configured`() {
        val m = AuthManager(mapOf("anthropic" to AnthropicAuthProvider("", null))) { "anthropic" }
        val r = m.preflight()
        assertFalse(r.valid)
        assertNotNull(r.message)
    }
    @Test fun `providerById returns correct provider`() {
        assertEquals("bedrock", createManager().providerById("bedrock")?.id)
    }
    @Test fun `providerById returns null for unknown id`() {
        assertNull(createManager().providerById("unknown"))
    }

    @Test fun `effectiveProviderId returns configured provider when configured`() {
        val m = createManager(activeProviderId = "anthropic")
        assertEquals("anthropic", m.effectiveProviderId())
    }

    @Test fun `effectiveProviderId falls back to any configured provider when configured is unconfigured`() {
        val m = AuthManager(
            mapOf(
                "anthropic" to AnthropicAuthProvider(apiKey = "", envApiKey = null),
                "bedrock" to BedrockAuthProvider(region = "us-east-1", bearerToken = "tok"),
            ),
        ) { "anthropic" }
        assertEquals("bedrock", m.effectiveProviderId())
    }

    @Test fun `effectiveProviderId returns configured id when nothing is configured`() {
        val m = AuthManager(
            mapOf(
                "anthropic" to AnthropicAuthProvider(apiKey = "", envApiKey = null),
                "bedrock" to BedrockAuthProvider(region = "", bearerToken = ""),
            ),
        ) { "anthropic" }
        assertEquals("anthropic", m.effectiveProviderId())
    }

    @Test fun `effectiveProviderId short-circuits when provider has no environment fallback`() {
        val m = AuthManager(
            mapOf(
                "anthropic" to AnthropicAuthProvider(apiKey = "sk-test", envApiKey = null),
                ProviderRegistry.OPENAI_COMPATIBLE_ID to object : AuthProvider {
                    override val id = ProviderRegistry.OPENAI_COMPATIBLE_ID
                    override fun isConfigured() = false
                    override fun applyToEnvironment(env: MutableMap<String, String>) {}
                    override fun validate() = AuthValidation(false, "test")
                    override fun testConnection() = ConnectionTestResult(false, "test")
                },
            ),
        ) { ProviderRegistry.OPENAI_COMPATIBLE_ID }
        assertEquals(ProviderRegistry.OPENAI_COMPATIBLE_ID, m.effectiveProviderId())
    }
}
