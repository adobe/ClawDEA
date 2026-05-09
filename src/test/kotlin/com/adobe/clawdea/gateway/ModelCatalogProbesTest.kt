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
package com.adobe.clawdea.gateway

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogProbesTest {

    @Test
    fun `forProvider returns AnthropicModelProbe for anthropic`() {
        val probe = ModelCatalogProbes.forProvider(
            providerId = "anthropic",
            anthropicApiKey = "sk-test", bedrockRegion = "", bedrockBearerToken = "",
        )
        assertTrue(probe is AnthropicModelProbe)
    }

    @Test
    fun `forProvider returns BedrockModelProbe for bedrock`() {
        val probe = ModelCatalogProbes.forProvider(
            providerId = "bedrock",
            anthropicApiKey = "", bedrockRegion = "us-east-1", bedrockBearerToken = "bearer-test",
        )
        assertTrue(probe is BedrockModelProbe)
    }

    @Test
    fun `forProvider returns SubscriptionModelProbe for subscription`() {
        val probe = ModelCatalogProbes.forProvider(
            providerId = "subscription",
            anthropicApiKey = "", bedrockRegion = "", bedrockBearerToken = "",
        )
        assertTrue(probe is SubscriptionModelProbe)
    }

    @Test
    fun `forProvider returns null for vertex`() {
        assertNull(
            ModelCatalogProbes.forProvider(
                providerId = "vertex",
                anthropicApiKey = "", bedrockRegion = "us-east-1", bedrockBearerToken = "bearer-test",
            )
        )
    }

    @Test
    fun `forProvider returns null for unknown`() {
        assertNull(
            ModelCatalogProbes.forProvider(
                providerId = "unknown",
                anthropicApiKey = "", bedrockRegion = "us-east-1", bedrockBearerToken = "bearer-test",
            )
        )
    }
}
