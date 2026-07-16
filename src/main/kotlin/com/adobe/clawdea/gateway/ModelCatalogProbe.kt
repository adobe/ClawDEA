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
import com.adobe.clawdea.provider.openai.catalog.OpenAiCompatibleModelProbe
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile

interface ModelCatalogProbe {
    /** Must be called off-EDT. Returns null on any failure. */
    fun probe(): List<ModelEntry>?
}

data class ModelProbeContext(
    val providerId: String,
    val profile: ResolvedProviderProfile? = null,
    val credential: String = "",
    val anthropicApiKey: String = "",
    val bedrockRegion: String = "",
    val bedrockBearerToken: String = "",
)

object ModelCatalogProbes {
    fun forProvider(context: ModelProbeContext): ModelCatalogProbe? =
        when (context.providerId) {
            "anthropic"                         -> AnthropicModelProbe(context.anthropicApiKey)
            "bedrock"                           -> BedrockModelProbe(context.bedrockRegion, context.bedrockBearerToken)
            "subscription"                      -> SubscriptionModelProbe()
            "openai-subscription"               -> CodexModelProbe()
            ProviderRegistry.OPENAI_COMPATIBLE_ID -> {
                val profile = context.profile ?: return null
                OpenAiCompatibleModelProbe(profile, context.credential)
            }
            else                                -> null
        }
}
