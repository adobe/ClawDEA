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
import org.junit.Assert.assertEquals
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
}
