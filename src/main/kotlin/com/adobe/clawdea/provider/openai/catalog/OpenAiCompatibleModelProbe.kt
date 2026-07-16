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
package com.adobe.clawdea.provider.openai.catalog

import com.adobe.clawdea.gateway.ModelCatalogProbe
import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.openai.client.OpenAiCompatibleClient
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
import com.intellij.openapi.diagnostic.Logger

/**
 * Populates the OpenAI-compatible model catalog by fetching models
 * from the provider's /models endpoint via the configured profile.
 */
class OpenAiCompatibleModelProbe(
    private val profile: ResolvedProviderProfile,
    private val credential: String,
    private val client: OpenAiCompatibleClient = OpenAiCompatibleClient(),
) : ModelCatalogProbe {

    private val log = Logger.getInstance(OpenAiCompatibleModelProbe::class.java)

    override fun probe(): List<ModelEntry>? {
        if (credential.isBlank()) {
            log.info("openai-compatible probe: no credential")
            return null
        }

        return client.listModels(profile, credential)
    }
}
