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
package com.adobe.clawdea.provider.openai.agent

import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile

/**
 * Resolves a model's context window (tokens) so the agent loop can size compaction.
 *
 * Sources, in priority order:
 *  1. The user-editable model catalog (the "Context window" column) — the authoritative UI source.
 *  2. The imported profile's `contextWindows` map — for profiles that ship the value in JSON.
 *  3. [DEFAULT_CONTEXT_WINDOW_TOKENS] — a conservative default so a small local model (e.g. a 128K
 *     Qwen) still gets compacted out of the box instead of overflowing its real window and looping.
 */
object ContextWindows {
    /**
     * Conservative default window (tokens) for a model with no configured value. 128K matches the
     * common local/open-weight size; a genuinely larger-context model should set its real value in
     * the models table so compaction doesn't fire earlier than necessary.
     */
    const val DEFAULT_CONTEXT_WINDOW_TOKENS = 131_072

    /** Profile-only lookup. Null when the profile does not declare a window for [modelId]. */
    fun forModel(profile: OpenAiCompatibleProfile, modelId: String): Int? =
        profile.contextWindows[modelId]?.takeIf { it > 0 }

    /**
     * Effective window used by the agent loop: catalog column → profile map → conservative default.
     * Never null — the loop always gets a real token budget for this backend.
     */
    fun resolve(profile: OpenAiCompatibleProfile, modelId: String, catalog: List<ModelEntry>): Int {
        val fromCatalog = catalog.firstOrNull { it.id == modelId }?.contextWindow?.takeIf { it > 0 }
        return fromCatalog ?: forModel(profile, modelId) ?: DEFAULT_CONTEXT_WINDOW_TOKENS
    }
}
