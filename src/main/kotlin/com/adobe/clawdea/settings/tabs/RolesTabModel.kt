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
package com.adobe.clawdea.settings.tabs

import com.adobe.clawdea.chat.ProviderModelOption
import com.adobe.clawdea.chat.ProviderModelSource
import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.AgentRole
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.provider.ProviderRegistry

/**
 * Computes whether the given [role] and [modelEntry] combination should display
 * a capability warning. True when [role] is [AgentRole.WIKI] AND either:
 * - The model's capability is "completion_only" (wiki authoring needs tool-capable models), OR
 * - The selection's provider is Codex (the in-chat librarian cannot run cross-provider and falls back to the chat model).
 */
fun computeCapabilityWarning(role: String, modelEntry: ModelEntry?, selection: AgentSelection? = null): Boolean {
    if (role != AgentRole.WIKI) return false
    if (modelEntry == null) return false

    // Warn for completion-only models
    if (modelEntry.capability == "completion_only") return true

    // Warn for Codex providers (in-chat librarian falls back to chat model)
    if (selection != null) {
        val backendKind = ProviderRegistry.require(selection.providerId).backendKind
        if (backendKind == BackendKind.CODEX_APP_SERVER) return true
    }

    return false
}

/**
 * Builds the provider+model option list for a role picker. Unlike [com.adobe.clawdea.chat.buildChatOptions],
 * which filters openai-compatible models to agentic-only, role pickers show ALL enabled models
 * (including completion_only) so users can explicitly choose a completion-only model for the
 * Completions role or inspect why a model might not be suitable for the Wiki role.
 *
 * @param sources The list of provider/profile catalogs to combine.
 * @return The combined, ordered list of role-picker entries.
 */
fun buildRoleOptions(sources: List<ProviderModelSource>): List<ProviderModelOption> {
    return sources.flatMap { source ->
        // For role pickers, include ALL enabled models regardless of capability
        val candidateModels = source.models.filter { it.enabled }

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
