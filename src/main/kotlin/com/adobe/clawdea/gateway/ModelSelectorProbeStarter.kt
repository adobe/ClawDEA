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
package com.adobe.clawdea.gateway

import com.adobe.clawdea.provider.ProviderRegistry
import com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore
import com.adobe.clawdea.provider.openai.profile.ProfileStore
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ModelSelectorProbeStarter {

    private val log = Logger.getInstance(ModelSelectorProbeStarter::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun probeIfApplicable(@Suppress("UNUSED_PARAMETER") project: Project) = runProbe()

    fun runProbe() {
        scope.launch {
            val settings = ClawDEASettings.getInstance()
            val authManager = com.adobe.clawdea.auth.AuthManager.getInstance()
            val provider = authManager.effectiveProviderId()
            val anthropic = authManager.providerById("anthropic") as? com.adobe.clawdea.auth.AnthropicAuthProvider
            val bedrock = authManager.providerById("bedrock") as? com.adobe.clawdea.auth.BedrockAuthProvider

            val context = if (provider == ProviderRegistry.OPENAI_COMPATIBLE_ID) {
                val activeProfileId = settings.state.activeOpenAiCompatibleProfileId
                if (activeProfileId.isBlank()) {
                    log.info("$provider probe: no active profile")
                    return@launch
                }
                val profile = ProfileStore(settings).resolve(activeProfileId, System.getenv())
                if (profile == null) {
                    log.info("$provider probe: profile $activeProfileId not resolved")
                    return@launch
                }
                val credential = ProfileCredentialStore().get(activeProfileId)
                ModelProbeContext(
                    providerId = provider,
                    profile = profile,
                    credential = credential,
                )
            } else {
                ModelProbeContext(
                    providerId = provider,
                    anthropicApiKey = anthropic?.getApiKey().orEmpty(),
                    bedrockRegion = bedrock?.resolvedRegion().orEmpty(),
                    bedrockBearerToken = bedrock?.resolvedBearerToken().orEmpty(),
                )
            }

            val probe = ModelCatalogProbes.forProvider(context) ?: return@launch

            val fetched = probe.probe() ?: run {
                log.info("$provider probe: no result; leaving catalog as-is")
                return@launch
            }

            val catalogKey = if (provider == ProviderRegistry.OPENAI_COMPATIBLE_ID) {
                ProviderRegistry.catalogKey(provider, settings.state.activeOpenAiCompatibleProfileId)
            } else {
                provider
            }

            val existing = settings.state.modelCatalogs[catalogKey] ?: mutableListOf()
            val merged = ModelCatalogMerger.merge(existing, fetched)
            settings.state.modelCatalogs[catalogKey] = merged.toMutableList()
            ApplicationManager.getApplication().messageBus
                .syncPublisher(ModelCatalogListener.TOPIC)
                .onCatalogUpdated()
        }
    }
}
