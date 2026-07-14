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
     * In Phase 1, `openai` is intentionally excluded from the env-fallback candidate
     * set: no wired backend can consume OpenAI credentials yet, so auto-selecting it
     * merely because `OPENAI_API_KEY` is exported would feed a `gpt-*` model to the
     * `claude` CLI. Explicit selection (configured == "openai") is unaffected.
     */
    fun effectiveProviderId(): String {
        val configured = configuredProviderId()
        val configuredProvider = providers[configured]
        if (configuredProvider?.isConfigured() == true) return configured
        // TODO(Phase 2): drop the openai exclusion once CodexProcess is wired — until a
        // backend can actually run OpenAI credentials, openai must not be auto-selected by
        // the env-fallback (it would feed a gpt-* model to the claude CLI). Explicit
        // selection (configured == "openai") is unaffected.
        val envProvider = providers.entries.firstOrNull { (id, p) -> id != configured && id != "openai" && p.isConfigured() }
        return envProvider?.key ?: configured
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

    companion object {
        fun getInstance(): AuthManager =
            ApplicationManager.getApplication().getService(AuthManager::class.java)
    }
}
