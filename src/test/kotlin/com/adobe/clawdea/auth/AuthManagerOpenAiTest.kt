package com.adobe.clawdea.auth

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
}
