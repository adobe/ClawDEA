package com.adobe.clawdea.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthManagerOpenAiTest {
    @Test
    fun `default registry resolves openai to an OpenAIAuthProvider`() {
        assertTrue(AuthManager().providerById("openai") is OpenAIAuthProvider)
    }

    @Test
    fun `default registry keeps the four existing providers`() {
        val mgr = AuthManager()
        assertTrue(mgr.providerById("anthropic") is AnthropicAuthProvider)
        assertTrue(mgr.providerById("bedrock") is BedrockAuthProvider)
        assertTrue(mgr.providerById("vertex") is VertexAuthProvider)
        assertTrue(mgr.providerById("subscription") is SubscriptionAuthProvider)
    }

    @Test
    fun `env-fallback does not auto-select openai for a configured anthropic user`() {
        val mgr = AuthManager(
            providers = mapOf(
                "anthropic" to AnthropicAuthProvider(apiKey = "", envApiKey = null),
                "openai" to OpenAIAuthProvider(apiKey = "sk-x", envApiKey = null),
            ),
            configuredProviderId = { "anthropic" },
        )
        assertEquals("anthropic", mgr.effectiveProviderId())
    }

    @Test
    fun `explicitly configured openai is still selectable`() {
        val mgr = AuthManager(
            providers = mapOf(
                "anthropic" to AnthropicAuthProvider(apiKey = "", envApiKey = null),
                "openai" to OpenAIAuthProvider(apiKey = "sk-x", envApiKey = null),
            ),
            configuredProviderId = { "openai" },
        )
        assertEquals("openai", mgr.effectiveProviderId())
    }

    @Test
    fun `safe env-fallback to bedrock is preserved`() {
        val mgr = AuthManager(
            providers = mapOf(
                "anthropic" to AnthropicAuthProvider(apiKey = "", envApiKey = null),
                "bedrock" to BedrockAuthProvider(region = "us-east-1", bearerToken = ""),
                "openai" to OpenAIAuthProvider(apiKey = "", envApiKey = null),
            ),
            configuredProviderId = { "anthropic" },
        )
        assertEquals("bedrock", mgr.effectiveProviderId())
    }
}
