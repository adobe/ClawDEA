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
import com.adobe.clawdea.provider.ProviderRegistry
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service

@Service(Service.Level.APP)
class AuthManager(
    private val providers: Map<String, AuthProvider>,
    private val configuredProviderId: () -> String,
) {

    constructor() : this(
        providers = mapOf(
            "anthropic" to AnthropicAuthProvider(),
            "bedrock" to BedrockAuthProvider(),
            "vertex" to VertexAuthProvider(),
            "subscription" to SubscriptionAuthProvider(),
            "openai" to OpenAIAuthProvider(),
            "openai-subscription" to OpenAiSubscriptionAuthProvider(),
            ProviderRegistry.OPENAI_COMPATIBLE_ID to com.adobe.clawdea.provider.openai.auth.OpenAiCompatibleAuthProvider(),
        ),
        configuredProviderId = { ClawDEASettings.getInstance().state.apiProvider },
    )

    /**
     * The provider id whose credentials will actually be used at runtime.
     *
     * Returns the user-configured provider when it reports [AuthProvider.isConfigured].
     * Otherwise, if the configured provider has no credentials but another provider does
     * (typically from environment variables the user exported outside of Settings),
     * returns that other provider's id. This keeps the model catalog, preflight check,
     * and CLI env in sync with whichever credentials the CLI will actually pick up.
     *
     * Falls back to the configured id when nothing is configured, so preflight still
     * produces the correct "not configured" message for the user's chosen provider.
     *
     * The OpenAI providers (`openai`, `openai-subscription`) are intentionally excluded from
     * the env-fallback candidate set. The codex backend is now wired, so *explicit* selection
     * (configured == "openai" / "openai-subscription") routes to `codex` — but codex
     * authenticates via its own `codex login` (`~/.codex/auth.json`), not a bare `OPENAI_API_KEY`
     * env var, so a user who merely exported that variable is not necessarily able to run codex.
     * Auto-hijacking a Claude user onto OpenAI on that weak signal would be surprising; keep the
     * fallback Claude-only and require explicit provider selection for codex.
     *
     * An explicitly configured codex provider is also *authoritative* and short-circuits the
     * fallback entirely: [OpenAiSubscriptionAuthProvider.isConfigured] reads an async, cache-backed
     * `codex login status` that returns a stale "not signed in" on the EDT until the first probe
     * completes. Without this short-circuit, a bridge constructed during that window would fall back
     * to a Claude provider and drive the `claude` CLI — but the model dropdown (resolved a moment
     * later, once the probe is warm) hands it an OpenAI model id, yielding
     * "API Error (gpt-5.x): 400 invalid model identifier". The user picked codex; honor it and let
     * a genuine sign-in problem surface as a codex auth error rather than a silent backend swap.
     */
    fun effectiveProviderId(): String {
        val configured = configuredProviderId()
        if (!ProviderRegistry.require(configured).allowEnvironmentFallback) return configured
        val configuredProvider = providers[configured]
        if (configuredProvider?.isConfigured() == true) return configured
        val envProvider = providers.entries.firstOrNull { (id, p) ->
            id != configured && ProviderRegistry.require(id).allowEnvironmentFallback && p.isConfigured()
        }
        return envProvider?.key ?: configured
    }

    /**
     * The key under which the model catalog and the user's selected model are stored
     * for the [effectiveProviderId]. For `openai-compatible` this is the composite
     * `openai-compatible:<activeProfileId>` so each imported profile keeps its own
     * catalog and selection; for every other provider it is the bare provider id
     * (see [ProviderRegistry.catalogKey]). Use this everywhere the model dropdown, the
     * catalog, and the backend factory agree on "which model did the user pick".
     */
    fun effectiveCatalogKey(): String {
        val providerId = effectiveProviderId()
        val profileId = if (providerId == ProviderRegistry.OPENAI_COMPATIBLE_ID) {
            ClawDEASettings.getInstance().state.activeOpenAiCompatibleProfileId
        } else {
            ""
        }
        return ProviderRegistry.catalogKey(providerId, profileId)
    }

    fun activeProvider(): AuthProvider {
        val id = effectiveProviderId()
        return providers[id] ?: providers["anthropic"]!!
    }

    fun applyToEnvironment(env: MutableMap<String, String>) {
        activeProvider().applyToEnvironment(env)
    }

    fun preflight(): AuthValidation = activeProvider().validate()

    fun providerById(id: String): AuthProvider? = providers[id]

    /**
     * Resolves the [AuthProvider] for the given [AgentSelection], returning the requested provider
     * by its [AgentSelection.providerId], with a fallback to anthropic if not found.
     */
    fun providerFor(sel: AgentSelection): AuthProvider =
        providers[sel.providerId] ?: providers.getValue("anthropic")

    /**
     * Returns whether the provider specified in the [AgentSelection] is authenticated.
     */
    fun isAuthenticated(sel: AgentSelection): Boolean = providerFor(sel).isConfigured()

    /**
     * Applies the credentials of the provider specified in the [AgentSelection] to the environment.
     */
    fun applyToEnvironment(env: MutableMap<String, String>, sel: AgentSelection) {
        providerFor(sel).applyToEnvironment(env)
    }

    companion object {
        fun getInstance(): AuthManager =
            ApplicationManager.getApplication().getService(AuthManager::class.java)
    }
}
