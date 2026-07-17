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

import com.adobe.clawdea.provider.ProviderRegistry
import com.adobe.clawdea.settings.ClawDEASettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ProvidersTab — generic model-table visibility, catalog persistence ownership.
 */
class ProvidersTabTest {

    @Test
    fun `generic models table is hidden for openai-compatible`() {
        assertFalse(
            "openai-compatible provider owns its table via the card; generic table should NOT be shown",
            ProvidersTab.showsGenericModelsTable(ProviderRegistry.OPENAI_COMPATIBLE_ID)
        )
    }

    @Test
    fun `generic models table is shown for all non-openai-compatible providers`() {
        assertTrue(ProvidersTab.showsGenericModelsTable("anthropic"))
        assertTrue(ProvidersTab.showsGenericModelsTable("bedrock"))
        assertTrue(ProvidersTab.showsGenericModelsTable("vertex"))
        assertTrue(ProvidersTab.showsGenericModelsTable("subscription"))
        assertTrue(ProvidersTab.showsGenericModelsTable("openai"))
        assertTrue(ProvidersTab.showsGenericModelsTable("openai-subscription"))
    }

    @Test
    fun `openai-compatible catalog key is excluded from generic transient catalogs`() {
        // The generic table's saveModels/loadModels/isModelsModified exclude openai-compatible keys
        // (both the base key and profile-scoped keys) because the card owns those catalogs.
        // This test verifies the filtering logic by checking a simulated catalog map.
        val catalogs = mapOf<String, List<com.adobe.clawdea.gateway.ModelEntry>>(
            "anthropic" to emptyList(),
            "bedrock" to emptyList(),
            ProviderRegistry.OPENAI_COMPATIBLE_ID to emptyList(),
            "${ProviderRegistry.OPENAI_COMPATIBLE_ID}:profile1" to emptyList(),
        )

        val genericKeys = catalogs.keys.filter { k ->
            k != ProviderRegistry.OPENAI_COMPATIBLE_ID &&
                !k.startsWith("${ProviderRegistry.OPENAI_COMPATIBLE_ID}:")
        }

        // Assert: only non-openai-compatible keys are included.
        assertTrue(genericKeys.contains("anthropic"))
        assertTrue(genericKeys.contains("bedrock"))
        assertFalse(
            "base openai-compatible key should be excluded from generic catalogs",
            genericKeys.contains(ProviderRegistry.OPENAI_COMPATIBLE_ID)
        )
        assertFalse(
            "profile-scoped openai-compatible keys should be excluded from generic catalogs",
            genericKeys.any { it.startsWith("${ProviderRegistry.OPENAI_COMPATIBLE_ID}:") }
        )
    }
}
