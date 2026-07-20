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
package com.adobe.clawdea.provider
import com.adobe.clawdea.settings.ClawDEASettings
import com.google.gson.Gson
class RoleSelectionStore(private val settings: ClawDEASettings) {
    private val gson = Gson()
    fun get(role: String): AgentSelection {
        settings.state.roleSelections[role]?.let { return gson.fromJson(it, AgentSelection::class.java) }
        // Fallback default: legacy provider (or anthropic) with its global selected model.
        // For openai-compatible, snapshot the current global active profile so the selection
        // is self-contained (does not depend on the global active profile later changing).
        val providerId = settings.state.apiProvider.ifBlank { "anthropic" }
        val modelId = settings.getSelectedModelId("", providerId)
        val profileId = if (providerId == ProviderRegistry.OPENAI_COMPATIBLE_ID) {
            settings.state.activeOpenAiCompatibleProfileId.ifBlank { null }
        } else {
            null
        }
        return AgentSelection(providerId, profileId, modelId)
    }
    fun set(role: String, sel: AgentSelection) { settings.state.roleSelections[role] = gson.toJson(sel) }
    fun migrateFromLegacyIfNeeded() {
        if (settings.state.roleSelectionsMigrated) return
        val seed = get(AgentRole.CHAT_DEFAULT) // computes from legacy
        listOf(AgentRole.CHAT_DEFAULT, AgentRole.WIKI, AgentRole.COMPLETIONS).forEach { set(it, seed) }
        settings.state.roleSelectionsMigrated = true
    }
}
