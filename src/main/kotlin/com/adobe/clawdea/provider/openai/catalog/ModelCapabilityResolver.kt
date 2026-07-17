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
}
