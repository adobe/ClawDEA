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
    fun `isGenericCatalogKey excludes openai-compatible base and composite keys`() {
        // loadModels/saveModels/isModelsModified all route through this single predicate, so
        // asserting it here guarantees all three seams are symmetric and cannot drift.
        assertTrue(ProvidersTab.isGenericCatalogKey("anthropic"))
        assertTrue(ProvidersTab.isGenericCatalogKey("bedrock"))
        assertTrue(ProvidersTab.isGenericCatalogKey("vertex"))
        assertFalse(
            "base openai-compatible key is card-owned, not generic",
            ProvidersTab.isGenericCatalogKey(ProviderRegistry.OPENAI_COMPATIBLE_ID)
        )
        assertFalse(
            "composite openai-compatible:<profile> key is card-owned, not generic",
            ProvidersTab.isGenericCatalogKey("${ProviderRegistry.OPENAI_COMPATIBLE_ID}:profile1")
        )
        assertFalse(
            ProvidersTab.isGenericCatalogKey("${ProviderRegistry.OPENAI_COMPATIBLE_ID}:p2")
        )
    }
}
