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
    fun `default registry resolves openai-subscription to an OpenAiSubscriptionAuthProvider`() {
        assertTrue(AuthManager().providerById("openai-subscription") is OpenAiSubscriptionAuthProvider)
    }

    @Test
    fun `env-fallback does not auto-select openai-subscription for a configured anthropic user`() {
        // A Claude user (unconfigured anthropic — relying on the claude CLI's own cached login)
        // who happens to be signed into codex must NOT be silently switched to openai-subscription:
        // there is no codex chat backend yet, so it would feed a gpt-* model to the claude CLI.
        val mgr = AuthManager(
            providers = mapOf(
                "anthropic" to AnthropicAuthProvider(apiKey = "", envApiKey = null),
                "openai-subscription" to OpenAiSubscriptionAuthProvider(isSignedIn = true),
            ),
            configuredProviderId = { "anthropic" },
        )
        assertEquals("anthropic", mgr.effectiveProviderId())
    }

    @Test
    fun `explicitly configured openai-subscription is still selectable`() {
        val mgr = AuthManager(
            providers = mapOf(
                "anthropic" to AnthropicAuthProvider(apiKey = "", envApiKey = null),
                "openai-subscription" to OpenAiSubscriptionAuthProvider(isSignedIn = true),
            ),
            configuredProviderId = { "openai-subscription" },
        )
        assertEquals("openai-subscription", mgr.effectiveProviderId())
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
    fun `configured openai-subscription never falls back to a claude backend when sign-in probe is not-ready`() {
        // Regression: OpenAiSubscriptionAuthProvider.isConfigured() reads an async codex-login probe
        // that returns a stale "not signed in" on the EDT until the first probe completes. If the
        // bridge is constructed in that window, effectiveProviderId must NOT fall back to a signed-in
        // Claude provider (that would drive the claude CLI with a gpt-* model -> 400). The explicit
        // codex choice is authoritative; a real sign-in problem surfaces later as a codex auth error.
        val mgr = AuthManager(
            providers = mapOf(
                "bedrock" to BedrockAuthProvider(region = "us-east-1", bearerToken = "tok"),
                "openai-subscription" to OpenAiSubscriptionAuthProvider(isSignedIn = false),
            ),
            configuredProviderId = { "openai-subscription" },
        )
        assertEquals("openai-subscription", mgr.effectiveProviderId())
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
