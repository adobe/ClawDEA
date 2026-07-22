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

import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.openai.profile.ModelRule

enum class ModelCapability {
    COMPLETION_ONLY,
    AGENTIC,
    UNKNOWN,
}

object ModelCapabilityResolver {

    /**
     * Resolves model capability using conservative precedence:
     * userOverride > endpointCapability > matchingRule > COMPLETION_ONLY.
     */
    fun resolve(
        modelId: String,
        endpointCapability: ModelCapability?,
        profileRules: List<ModelRule>,
        userOverride: ModelCapability?,
    ): ModelCapability {
        if (userOverride != null) return userOverride
        if (endpointCapability != null) return endpointCapability
        val matchingRule = findMatchingRule(modelId, profileRules)
        if (matchingRule != null) return parseCapability(matchingRule.capability)
        return ModelCapability.COMPLETION_ONLY
    }

    /**
     * The empirically-verified capability persisted for [modelId] by "Refresh Models" (which probes
     * each model and stores the verdict on its [ModelEntry.capability]). Feed the result into
     * [resolve]'s `endpointCapability` slot so a verified-agentic model drives agentic chat even when
     * no profile rule matches it.
     *
     * Returns only a DEFINITE verdict (AGENTIC / COMPLETION_ONLY); an `unknown` entry (probe failed
     * or never run) or a model absent from [catalog] yields null so profile rules still decide — a
     * transport failure must never override a user-authored agentic rule.
     */
    fun catalogCapability(modelId: String, catalog: List<ModelEntry>): ModelCapability? {
        val entry = catalog.firstOrNull { it.id == modelId } ?: return null
        return when (parseCapability(entry.capability)) {
            ModelCapability.AGENTIC -> ModelCapability.AGENTIC
            ModelCapability.COMPLETION_ONLY -> ModelCapability.COMPLETION_ONLY
            ModelCapability.UNKNOWN -> null
        }
    }

    private fun findMatchingRule(modelId: String, rules: List<ModelRule>): ModelRule? {
        for (rule in rules) {
            if (matchesGlob(modelId, rule.pattern)) {
                return rule
            }
        }
        return null
    }

    private fun matchesGlob(modelId: String, pattern: String): Boolean {
        // Match-all: the glob "*" and the regex-style ".*" (a very common authoring mistake, since
        // people reach for regex) both mean "every model". Treat them identically so a profile that
        // writes ".*" to mark all models agentic doesn't silently fall through to COMPLETION_ONLY.
        if (pattern == "*" || pattern == ".*") return true
        if (!pattern.contains("*")) return modelId == pattern

        // Convert glob to regex: escape literal parts, replace * with .*
        val segments = pattern.split("*")
        val regexPattern = segments.joinToString(".*") { Regex.escape(it) }
        return Regex("^$regexPattern\$").matches(modelId)
    }

    private fun parseCapability(capability: String): ModelCapability {
        return when (capability.lowercase()) {
            "agentic" -> ModelCapability.AGENTIC
            "completion_only" -> ModelCapability.COMPLETION_ONLY
            else -> ModelCapability.UNKNOWN
        }
    }

    /**
     * Serialize a [ModelCapability] to the string stored in [com.adobe.clawdea.gateway.ModelEntry.capability]
     * (the inverse of [parseCapability]): AGENTIC -> "agentic", COMPLETION_ONLY -> "completion_only",
     * UNKNOWN -> "unknown".
     */
    fun capabilityToString(capability: ModelCapability): String = when (capability) {
        ModelCapability.AGENTIC -> "agentic"
        ModelCapability.COMPLETION_ONLY -> "completion_only"
        ModelCapability.UNKNOWN -> "unknown"
    }
}
