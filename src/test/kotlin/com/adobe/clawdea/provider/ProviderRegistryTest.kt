package com.adobe.clawdea.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryTest {
    @Test
    fun `built-ins preserve backend selection`() {
        assertEquals(BackendKind.CLAUDE_CLI, ProviderRegistry.require("anthropic").backendKind)
        assertEquals(BackendKind.CLAUDE_CLI, ProviderRegistry.require("bedrock").backendKind)
        assertEquals(BackendKind.CODEX_APP_SERVER, ProviderRegistry.require("openai").backendKind)
        assertEquals(BackendKind.CODEX_APP_SERVER, ProviderRegistry.require("openai-subscription").backendKind)
    }

    @Test
    fun `generic provider uses HTTP backend and gateway features`() {
        val descriptor = ProviderRegistry.require(ProviderRegistry.OPENAI_COMPATIBLE_ID)
        assertEquals(BackendKind.OPENAI_COMPATIBLE_HTTP, descriptor.backendKind)
        assertTrue(descriptor.supportsInlineCompletions)
        assertTrue(descriptor.supportsIntentionActions)
    }

    @Test
    fun `profile catalog keys are isolated`() {
        assertEquals(
            "openai-compatible:profile-a",
            ProviderRegistry.catalogKey("openai-compatible", "profile-a"),
        )
        assertEquals("anthropic", ProviderRegistry.catalogKey("anthropic", "ignored"))
    }
}
