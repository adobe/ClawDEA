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
package com.adobe.clawdea.cli

import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.provider.ProviderRegistry
import com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore
import com.adobe.clawdea.settings.ClawDEASettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies CliBridge threading of AgentSelection: construction accepts an explicit selection,
 * falls back to effective-provider default when null, and exposes the resolved selection.
 */
class CliBridgeSelectionTest {

    @Test
    fun `explicit AgentSelection drives backend kind and is exposed via selection property`() {
        // Configure settings so "openai-compatible" is fully resolved: profile + model selected.
        val settings = ClawDEASettings()
        settings.state.importedOpenAiProfiles["apc"] =
            """{"id":"apc","name":"APC","baseUrl":"https://example.com","modelRules":[{"pattern":"*","capability":"agentic"}]}"""
        val catalogKey = ProviderRegistry.catalogKey(ProviderRegistry.OPENAI_COMPATIBLE_ID, "apc")
        settings.state.modelCatalogs[catalogKey] =
            mutableListOf(com.adobe.clawdea.gateway.ModelEntry(id = "m"))
        settings.state.selectedModels["$catalogKey|"] = "m"

        val selection = AgentSelection(
            providerId = ProviderRegistry.OPENAI_COMPATIBLE_ID,
            profileId = "apc",
            modelId = "m",
        )

        val bridge = CliBridge(
            workingDirectory = "",
            selection = selection,
            settings = settings,
            credentialStore = ProfileCredentialStore(),
        )

        assertEquals(BackendKind.OPENAI_COMPATIBLE_HTTP, bridge.backendKind)
        assertEquals(selection, bridge.selection)
    }

    @Test
    fun `null selection falls back to effective provider default and exposes resolved selection`() {
        // The effective provider is resolved via the injectable seam (production uses AuthManager's
        // env-fallthrough). Here we inject "anthropic" directly.
        val settings = ClawDEASettings()

        val bridge = CliBridge(
            workingDirectory = "",
            selection = null, // the default
            settings = settings,
            credentialStore = ProfileCredentialStore(),
            effectiveProviderIdProvider = { "anthropic" },
        )

        assertEquals(BackendKind.CLAUDE_CLI, bridge.backendKind)
        // The bridge should expose the resolved selection: anthropic provider
        assertEquals("anthropic", bridge.selection.providerId)
    }

    @Test
    fun `null selection honors effective-provider env-fallthrough not configured apiProvider`() {
        // Regression: the default selection MUST use the effective provider (AuthManager's
        // env-fallthrough), NOT the configured apiProvider. Scenario: apiProvider is "anthropic"
        // but only Bedrock is credentialed, so effectiveProviderId() resolves to "bedrock". The
        // bridge must build the bedrock backend, not anthropic (which would auth-fail).
        val settings = ClawDEASettings()
        settings.state.apiProvider = "anthropic" // configured, but NOT the effective provider

        val bridge = CliBridge(
            workingDirectory = "",
            selection = null,
            settings = settings,
            credentialStore = ProfileCredentialStore(),
            effectiveProviderIdProvider = { "bedrock" }, // env-fallthrough result
        )

        // Bedrock is a Claude-CLI backend, same as anthropic — but the selection must carry bedrock.
        assertEquals(BackendKind.CLAUDE_CLI, bridge.backendKind)
        assertEquals(
            "default selection must use the effective provider (bedrock), not configured apiProvider (anthropic)",
            "bedrock",
            bridge.selection.providerId,
        )
    }
}
