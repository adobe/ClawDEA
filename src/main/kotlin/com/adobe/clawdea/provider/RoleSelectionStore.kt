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
import com.adobe.clawdea.auth.AuthManager
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
    fun migrateFromLegacyIfNeeded(
        // Test seam: inject the AuthManager so tests can control which providers appear
        // authenticated without depending on the real machine's OS keychain/CLI auth state.
        // Production reads the application-service singleton.
        authManager: AuthManager = AuthManager.getInstance(),
    ) {
        if (settings.state.roleSelectionsMigrated) return
        // Fresh install (or first load after upgrade): seed smart per-role defaults — a middle-tier
        // model for chat, a lower/cheaper one for wiki + completions — computed from whichever
        // provider is authenticated. Falls back to cloning the legacy pick across all three roles
        // when nothing is authenticated yet (no auth => resolver returns null).
        applyDefaults(authManager)
        settings.state.roleSelectionsMigrated = true
    }

    /**
     * Overwrite all three roles with the current smart defaults. Backs the Settings "Reset defaults"
     * action; also the fresh-install seed. When no provider is authenticated, [RoleDefaults.compute]
     * returns null and we fall back to the legacy behavior (clone the computed CHAT_DEFAULT fallback
     * across all roles) so the roles are never left blank.
     */
    fun applyDefaults(authManager: AuthManager = AuthManager.getInstance()) {
        val smart = try {
            RoleDefaults.compute(settings, authManager)
        } catch (_: Throwable) {
            null
        }
        if (smart != null) {
            smart.forEach { (role, sel) -> set(role, sel) }
            return
        }
        val seed = get(AgentRole.CHAT_DEFAULT) // computes from legacy
        listOf(AgentRole.CHAT_DEFAULT, AgentRole.WIKI, AgentRole.COMPLETIONS).forEach { set(it, seed) }
    }
}
