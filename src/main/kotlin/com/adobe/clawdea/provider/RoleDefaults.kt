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
import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.openai.profile.ProfileStore
import com.adobe.clawdea.settings.ClawDEASettings

/**
 * Computes smart per-role model defaults (Chat / Wiki / Completions) from whichever provider
 * backend is actually available. The intent, in the user's words:
 *
 * - Claude backend (Bedrock or Claude subscription, or any Claude-CLI provider): latest **Opus**
 *   for chat, latest **Haiku** for wiki + completions.
 * - Codex backend only: latest **Terra** for chat, latest **Luna** for wiki + completions.
 * - OpenAI-compatible only: whatever agentic model is available.
 * - Generally: a middle-tier model for chat, a lower-tier model for wiki + completions (wiki and
 *   completions are cheap, high-volume roles; chat is the interactive one).
 *
 * This exists because a stale WIKI-role model (e.g. Bedrock Claude 3 Haiku, which rejects prompt
 * caching with an HTTP 400) silently broke the in-chat wiki librarian. Smart defaults keep each
 * role pointed at a caching-capable, task-appropriate model without the user hand-picking three.
 */
object RoleDefaultsResolver {

    /** One authenticated provider (or openai-compatible profile) and its enabled model catalog. */
    data class ProviderAvailability(
        val providerId: String,
        val profileId: String?,
        val backendKind: BackendKind,
        /** Enabled models, in catalog order (seed catalogs are ordered newest-first). */
        val models: List<ModelEntry>,
    )

    /** Preference order among authenticated Claude-CLI providers when several are available. */
    private val CLAUDE_PRIORITY = listOf("subscription", "bedrock", "anthropic", "vertex")

    /**
     * Resolves defaults for all three roles from [available], or null when nothing is available
     * (no authenticated provider) — the caller then keeps its legacy fallback.
     */
    fun resolve(available: List<ProviderAvailability>): Map<String, AgentSelection>? {
        val claude = available.filter { it.backendKind == BackendKind.CLAUDE_CLI }
        val codex = available.filter { it.backendKind == BackendKind.CODEX_APP_SERVER }
        val openai = available.filter { it.backendKind == BackendKind.OPENAI_COMPATIBLE_HTTP }

        return when {
            claude.isNotEmpty() -> {
                val p = claude.minByOrNull { priorityRank(it.providerId) }!!
                val chat = pick(p.models, preferred = listOf("opus"), fallback = listOf("sonnet"))
                    ?: p.models.firstOrNull()?.id.orEmpty()
                val low = pick(p.models, preferred = listOf("haiku"), fallback = listOf("sonnet"))
                    ?: p.models.lastOrNull()?.id.orEmpty()
                rolesFor(p, chat, low)
            }
            codex.isNotEmpty() -> {
                val p = codex.first()
                // Codex catalogs may be empty until the live probe runs; a blank modelId means the
                // account default model, which is always valid — so blank is an acceptable pick.
                val chat = pick(p.models, preferred = listOf("terra"), fallback = emptyList())
                    ?: p.models.firstOrNull()?.id.orEmpty()
                val low = pick(p.models, preferred = listOf("luna"), fallback = emptyList())
                    ?: p.models.lastOrNull()?.id.orEmpty()
                rolesFor(p, chat, low)
            }
            openai.isNotEmpty() -> {
                val p = openai.first()
                // Only agentic models can drive chat/wiki (they call tools); completion-only models
                // can't. Prefer the first agentic model for every role; fall back to first enabled.
                val agentic = p.models.firstOrNull { it.capability == "agentic" }?.id
                val any = p.models.firstOrNull()?.id.orEmpty()
                val model = agentic ?: any
                rolesFor(p, model, model)
            }
            else -> null
        }
    }

    private fun priorityRank(providerId: String): Int =
        CLAUDE_PRIORITY.indexOf(providerId).let { if (it < 0) Int.MAX_VALUE else it }

    /**
     * First model whose id contains one of [preferred] families (in order), else one of [fallback],
     * else null. Case-insensitive `contains` so it matches both plain ids (`claude-opus-4-8`) and
     * Bedrock inference-profile ids (`us.anthropic.claude-opus-4-7`).
     */
    private fun pick(models: List<ModelEntry>, preferred: List<String>, fallback: List<String>): String? {
        for (family in preferred + fallback) {
            models.firstOrNull { it.id.contains(family, ignoreCase = true) }?.let { return it.id }
        }
        return null
    }

    private fun rolesFor(p: ProviderAvailability, chatModel: String, lowModel: String): Map<String, AgentSelection> =
        mapOf(
            AgentRole.CHAT_DEFAULT to AgentSelection(p.providerId, p.profileId, chatModel),
            AgentRole.WIKI to AgentSelection(p.providerId, p.profileId, lowModel),
            AgentRole.COMPLETIONS to AgentSelection(p.providerId, p.profileId, lowModel),
        )
}

/**
 * Bridges [RoleDefaultsResolver] to live [ClawDEASettings] + [AuthManager]: enumerates every
 * authenticated provider (one entry per authenticated openai-compatible profile, active profile
 * first) with its enabled catalog, then resolves per-role defaults.
 */
object RoleDefaults {

    /** Smart per-role defaults from current auth + catalogs, or null when nothing is authenticated. */
    fun compute(settings: ClawDEASettings, auth: AuthManager): Map<String, AgentSelection>? =
        RoleDefaultsResolver.resolve(availability(settings, auth))

    private fun availability(settings: ClawDEASettings, auth: AuthManager): List<RoleDefaultsResolver.ProviderAvailability> {
        val state = settings.state
        val out = mutableListOf<RoleDefaultsResolver.ProviderAvailability>()
        for (descriptor in ProviderRegistry.all()) {
            if (descriptor.id == ProviderRegistry.OPENAI_COMPATIBLE_ID) {
                val activeId = state.activeOpenAiCompatibleProfileId
                // Active profile first so it wins when several profiles are authenticated.
                val profiles = ProfileStore(settings).profiles()
                    .sortedByDescending { it.id == activeId }
                for (profile in profiles) {
                    val sel = AgentSelection(descriptor.id, profile.id, "")
                    if (!auth.isAuthenticated(sel)) continue
                    val catalogKey = ProviderRegistry.catalogKey(descriptor.id, profile.id)
                    out += RoleDefaultsResolver.ProviderAvailability(
                        descriptor.id, profile.id, descriptor.backendKind, enabledModels(state, catalogKey),
                    )
                }
            } else {
                val sel = AgentSelection(descriptor.id, null, "")
                if (!auth.isAuthenticated(sel)) continue
                out += RoleDefaultsResolver.ProviderAvailability(
                    descriptor.id, null, descriptor.backendKind, enabledModels(state, descriptor.id),
                )
            }
        }
        return out
    }

    private fun enabledModels(state: ClawDEASettings.State, catalogKey: String): List<ModelEntry> =
        state.modelCatalogs[catalogKey]?.filter { it.enabled } ?: emptyList()
}
