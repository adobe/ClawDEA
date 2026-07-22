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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelCapabilityResolverTest {

    @Test
    fun `capability precedence`() {
        val rules = listOf(
            ModelRule(pattern = "gpt-*", capability = "agentic"),
        )
        assertEquals(
            ModelCapability.AGENTIC,
            ModelCapabilityResolver.resolve(
                modelId = "gpt-4",
                endpointCapability = null,
                profileRules = rules,
                userOverride = null,
            ),
        )
        assertEquals(
            ModelCapability.COMPLETION_ONLY,
            ModelCapabilityResolver.resolve(
                modelId = "gpt-4",
                endpointCapability = null,
                profileRules = rules,
                userOverride = ModelCapability.COMPLETION_ONLY,
            ),
        )
    }

    @Test
    fun `endpoint capability trumps rule`() {
        val rules = listOf(
            ModelRule(pattern = "unknown-*", capability = "completion_only"),
        )
        assertEquals(
            ModelCapability.AGENTIC,
            ModelCapabilityResolver.resolve(
                modelId = "unknown-model",
                endpointCapability = ModelCapability.AGENTIC,
                profileRules = rules,
                userOverride = null,
            ),
        )
    }

    @Test
    fun `fallback to completion only when all null or empty`() {
        assertEquals(
            ModelCapability.COMPLETION_ONLY,
            ModelCapabilityResolver.resolve(
                modelId = "unknown-model",
                endpointCapability = null,
                profileRules = emptyList(),
                userOverride = null,
            ),
        )
    }

    @Test
    fun `catalogCapability reads a verified agentic entry as endpoint capability`() {
        // Regression: "Refresh Models" probes each model and persists the verified capability into
        // the catalog. A model verified AGENTIC must resolve as AGENTIC even when no profile rule
        // matches it (the reported NVIDIA NIM failure: the verified model fell through to
        // COMPLETION_ONLY because only the profile rules were consulted).
        val catalog = listOf(ModelEntry(id = "nvidia/nemotron", capability = "agentic"))
        assertEquals(
            ModelCapability.AGENTIC,
            ModelCapabilityResolver.catalogCapability("nvidia/nemotron", catalog),
        )
        assertEquals(
            ModelCapability.AGENTIC,
            ModelCapabilityResolver.resolve(
                modelId = "nvidia/nemotron",
                endpointCapability = ModelCapabilityResolver.catalogCapability("nvidia/nemotron", catalog),
                profileRules = emptyList(),
                userOverride = null,
            ),
        )
    }

    @Test
    fun `catalogCapability treats unknown or absent entries as no signal`() {
        // A model whose probe failed (capability="unknown") or one that isn't in the catalog must
        // yield null so profile rules still decide — a transport failure must never override a
        // user-authored agentic rule.
        val catalog = listOf(ModelEntry(id = "probed", capability = "unknown"))
        assertNull(ModelCapabilityResolver.catalogCapability("probed", catalog))
        assertNull(ModelCapabilityResolver.catalogCapability("not-in-catalog", catalog))
    }

    @Test
    fun `catalogCapability reads a verified completion-only entry`() {
        val catalog = listOf(ModelEntry(id = "text-only", capability = "completion_only"))
        assertEquals(
            ModelCapability.COMPLETION_ONLY,
            ModelCapabilityResolver.catalogCapability("text-only", catalog),
        )
    }

    @Test
    fun `star and regex-style match-all patterns both match any model id`() {
        val slashyId = "hosted_vllm/openai/gpt-oss-120b"
        listOf("*", ".*").forEach { pattern ->
            assertEquals(
                "pattern '$pattern' should mark all models agentic",
                ModelCapability.AGENTIC,
                ModelCapabilityResolver.resolve(
                    modelId = slashyId,
                    endpointCapability = null,
                    profileRules = listOf(ModelRule(pattern = pattern, capability = "agentic")),
                    userOverride = null,
                ),
            )
        }
    }
}
