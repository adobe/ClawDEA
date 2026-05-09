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

interface ModelCatalogProbe {
    /** Must be called off-EDT. Returns null on any failure. */
    fun probe(): List<ModelEntry>?
}

object ModelCatalogProbes {
    fun forProvider(
        providerId: String,
        anthropicApiKey: String,
        bedrockRegion: String,
        bedrockBearerToken: String,
    ): ModelCatalogProbe? =
        when (providerId) {
            "anthropic"    -> AnthropicModelProbe(anthropicApiKey)
            "bedrock"      -> BedrockModelProbe(bedrockRegion, bedrockBearerToken)
            "subscription" -> SubscriptionModelProbe()
            else           -> null
        }
}
