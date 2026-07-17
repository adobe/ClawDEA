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

import com.adobe.clawdea.provider.AgentSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthManagerSelectionTest {

    private fun createManager(
        activeProviderId: String = "anthropic",
        providers: Map<String, AuthProvider> = mapOf(
            "anthropic" to AnthropicAuthProvider(apiKey = "sk-test", envApiKey = null),
            "bedrock" to BedrockAuthProvider(region = "us-east-1", bearerToken = "tok"),
            "vertex" to VertexAuthProvider(region = "us-central1", projectId = "proj"),
        ),
    ): AuthManager = AuthManager(providers) { activeProviderId }

    @Test
    fun `providerFor returns the selected provider verbatim (no fallthrough)`() {
        val am = createManager()
        assertEquals("bedrock", am.providerFor(AgentSelection("bedrock")).id)
    }

    @Test
    fun `isAuthenticated reflects the selected provider's configured state`() {
        val am = createManager()
        // anthropic with a key → authenticated
        assertTrue(am.isAuthenticated(AgentSelection("anthropic")))
        // vertex with config → authenticated
        assertTrue(am.isAuthenticated(AgentSelection("vertex")))
    }

    @Test
    fun `isAuthenticated returns false for unconfigured provider`() {
        val am = AuthManager(
            mapOf("anthropic" to AnthropicAuthProvider(apiKey = "", envApiKey = null))
        ) { "anthropic" }
        assertFalse(am.isAuthenticated(AgentSelection("anthropic")))
    }

    @Test
    fun `applyToEnvironment uses the selected provider's credentials`() {
        val am = createManager()
        val env = mutableMapOf<String, String>()
        am.applyToEnvironment(env, AgentSelection("bedrock"))
        assertEquals("1", env["CLAUDE_CODE_USE_BEDROCK"])
    }

    @Test
    fun `providerFor falls back to anthropic for unknown provider id`() {
        val am = createManager()
        assertEquals("anthropic", am.providerFor(AgentSelection("unknown-provider")).id)
    }
}
