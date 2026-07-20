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
package com.adobe.clawdea.chat

import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSelectableModelsTest {

    private val catalog = listOf(
        ModelEntry(id = "agentic-on", displayName = "Agentic Enabled", capability = "agentic", enabled = true),
        ModelEntry(id = "agentic-off", displayName = "Agentic Disabled", capability = "agentic", enabled = false),
        ModelEntry(id = "completion", displayName = "Completion Only", capability = "completion_only", enabled = true),
        ModelEntry(id = "unknown", displayName = "Unknown", capability = "unknown", enabled = true),
    )

    @Test
    fun `openai-compatible keeps only agentic and enabled models`() {
        val result = ModelComboManager.chatSelectableModels(catalog, ProviderRegistry.OPENAI_COMPATIBLE_ID)
        assertEquals(listOf("agentic-on"), result.map { it.id })
    }

    @Test
    fun `non-openai-compatible provider catalog is not filtered`() {
        // Claude/Codex catalogs carry no per-model capability semantics; return them unchanged.
        val result = ModelComboManager.chatSelectableModels(catalog, "anthropic")
        assertEquals(catalog.map { it.id }, result.map { it.id })
    }
}
