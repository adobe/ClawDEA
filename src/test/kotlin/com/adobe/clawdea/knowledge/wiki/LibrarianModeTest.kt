package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.provider.AgentSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarianModeTest {
    private fun sel(provider: String) = AgentSelection(providerId = provider, profileId = null, modelId = "m")

    @Test fun anthropic_is_claude_subagent() =
        assertEquals(LibrarianMode.CLAUDE_SUBAGENT, chooseLibrarianMode(sel("anthropic")))

    @Test fun bedrock_is_claude_subagent() =
        assertEquals(LibrarianMode.CLAUDE_SUBAGENT, chooseLibrarianMode(sel("bedrock")))

    @Test fun subscription_is_claude_subagent() =
        assertEquals(LibrarianMode.CLAUDE_SUBAGENT, chooseLibrarianMode(sel("subscription")))

    @Test fun openai_compatible_is_agentic_tool() =
        assertEquals(LibrarianMode.AGENTIC_MCP_TOOL, chooseLibrarianMode(sel("openai-compatible")))

    @Test fun codex_is_fallback() =
        assertEquals(LibrarianMode.CLAUDE_SUBAGENT_FALLBACK, chooseLibrarianMode(sel("openai")))

    @Test fun unknown_provider_defaults_to_claude_subagent() =
        assertEquals(LibrarianMode.CLAUDE_SUBAGENT, chooseLibrarianMode(sel("totally-unknown")))
}
