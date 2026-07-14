package com.adobe.clawdea.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIAuthProviderTest {
    @Test
    fun `id is openai`() {
        assertEquals("openai", OpenAIAuthProvider(apiKey = "", envApiKey = null).id)
    }

    @Test
    fun `env key takes precedence over stored key`() {
        val p = OpenAIAuthProvider(apiKey = "stored", envApiKey = "envkey")
        assertEquals("envkey", p.getApiKey())
    }

    @Test
    fun `falls back to stored key when env blank`() {
        val p = OpenAIAuthProvider(apiKey = "stored", envApiKey = null)
        assertEquals("stored", p.getApiKey())
    }

    @Test
    fun `not configured when no key anywhere`() {
        val p = OpenAIAuthProvider(apiKey = "", envApiKey = null)
        assertFalse(p.isConfigured())
        assertFalse(p.validate().valid)
    }

    @Test
    fun `configured when key present and sets env var`() {
        val p = OpenAIAuthProvider(apiKey = "sk-test", envApiKey = null)
        assertTrue(p.isConfigured())
        val env = mutableMapOf<String, String>()
        p.applyToEnvironment(env)
        assertEquals("sk-test", env["OPENAI_API_KEY"])
    }
}
