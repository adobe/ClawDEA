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
package com.adobe.clawdea.chat

import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.ProviderRegistry

/**
 * A single provider+model option in the chat model dropdown.
 * @property selection The [AgentSelection] value representing this choice (providerId, profileId, modelId).
 * @property label The display label shown in the dropdown UI (e.g., "Claude › Opus 4.8").
 * @property enabled Whether the option is currently usable (false if provider is unauthenticated).
 */
data class ProviderModelOption(
    val selection: AgentSelection,
    val label: String,
    val enabled: Boolean,
)

/**
 * A single provider or provider-profile catalog of models, input to [buildChatOptions].
 * @property providerId The provider ID (e.g., "anthropic", "openai-compatible").
 * @property profileId The profile ID (non-null for openai-compatible, null otherwise).
 * @property displayLabel The human-readable provider/profile label (e.g., "Claude", "My Provider").
 * @property authenticated Whether the provider/profile is currently authenticated.
 * @property models The list of models exposed by this provider/profile.
 */
data class ProviderModelSource(
    val providerId: String,
    val profileId: String?,
    val displayLabel: String,
    val authenticated: Boolean,
    val models: List<ModelEntry>,
)

/**
 * Builds the combined provider+model option list for the chat dropdown.
 *
 * Rules:
 * - For openai-compatible providers: only include models with `capability == "agentic" && enabled`.
 * - For other providers: include all `enabled == true` models, regardless of capability.
 * - Unauthenticated sources: emit options with `enabled = false` and label suffix " (sign in)".
 * - Label format: `"${source.displayLabel} › ${model.displayName}"` (using U+203A right-angle-quote).
 * - Order: source order is preserved; model order within each source is preserved.
 *
 * @param sources The list of provider/profile catalogs to combine.
 * @return The combined, ordered, filtered list of chat dropdown entries.
 */
fun buildChatOptions(sources: List<ProviderModelSource>): List<ProviderModelOption> {
    return sources.flatMap { source ->
        val candidateModels = if (source.providerId == ProviderRegistry.OPENAI_COMPATIBLE_ID) {
            source.models.filter { it.capability == "agentic" && it.enabled }
        } else {
            source.models.filter { it.enabled }
        }

        candidateModels.map { model ->
            val modelDisplayName = if (model.displayName.isNotBlank()) model.displayName else model.id
            val labelBase = "${source.displayLabel} › $modelDisplayName"
            val labelFinal = if (source.authenticated) labelBase else "$labelBase (sign in)"
            ProviderModelOption(
                selection = AgentSelection(source.providerId, source.profileId, model.id),
                label = labelFinal,
                enabled = source.authenticated,
            )
        }
    }
}
