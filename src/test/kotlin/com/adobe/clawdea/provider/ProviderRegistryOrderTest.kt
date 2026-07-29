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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings Providers combo is generated from this registry, so declaration order IS the
 * on-screen order and every descriptor must carry the long settings label. Previously the tab
 * hand-maintained a parallel PROVIDERS/PROVIDER_KEYS pair that could silently drift.
 */
class ProviderRegistryOrderTest {

    @Test
    fun `ordered ids match the historical settings combo order`() {
        assertEquals(
            listOf(
                "anthropic",
                "bedrock",
                "vertex",
                "subscription",
                "openai",
                "openai-subscription",
                "openai-compatible",
            ),
            ProviderRegistry.orderedIds(),
        )
    }

    @Test
    fun `settings labels match the historical combo labels`() {
        assertEquals(
            listOf(
                "Anthropic (direct)",
                "Amazon Bedrock",
                "Google Vertex AI",
                "Claude subscription (Pro / Max / Team / Enterprise)",
                "OpenAI (direct)",
                "OpenAI (ChatGPT subscription)",
                "OpenAI-compatible",
            ),
            ProviderRegistry.settingsLabels(),
        )
    }

    @Test
    fun `ids and labels stay index-aligned`() {
        assertEquals(ProviderRegistry.orderedIds().size, ProviderRegistry.settingsLabels().size)
    }

    @Test
    fun `every descriptor has a non-blank settings label`() {
        for (descriptor in ProviderRegistry.all()) {
            assertTrue(
                "provider ${descriptor.id} needs a settings label",
                descriptor.settingsLabel.isNotBlank(),
            )
        }
    }
}
