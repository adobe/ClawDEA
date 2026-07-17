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
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelOptionsTest {

    @Test
    fun `openai-compatible source filters to agentic and enabled models only`() {
        val source = ProviderModelSource(
            providerId = ProviderRegistry.OPENAI_COMPATIBLE_ID,
            profileId = "prof1",
            displayLabel = "My Provider",
            authenticated = true,
            models = listOf(
                ModelEntry(id = "agentic-1", displayName = "Agentic Model 1", enabled = true, capability = "agentic"),
                ModelEntry(id = "completion-1", displayName = "Completion Only 1", enabled = true, capability = "completion_only"),
                ModelEntry(id = "agentic-2", displayName = "Agentic Model 2", enabled = false, capability = "agentic"),
                ModelEntry(id = "unknown-1", displayName = "Unknown Model", enabled = true, capability = "unknown"),
            )
        )

        val options = buildChatOptions(listOf(source))

        assertEquals(1, options.size)
        assertEquals("agentic-1", options[0].selection.modelId)
        assertEquals("My Provider › Agentic Model 1", options[0].label)
        assertTrue(options[0].enabled)
    }

    @Test
    fun `other providers pass enabled models regardless of capability`() {
        val source = ProviderModelSource(
            providerId = "anthropic",
            profileId = null,
            displayLabel = "Claude",
            authenticated = true,
            models = listOf(
                ModelEntry(id = "opus-4-8", displayName = "Opus 4.8", enabled = true, capability = "unknown"),
                ModelEntry(id = "sonnet-5", displayName = "Sonnet 5", enabled = true, capability = "agentic"),
                ModelEntry(id = "haiku-4-5", displayName = "Haiku 4.5", enabled = false, capability = "agentic"),
            )
        )

        val options = buildChatOptions(listOf(source))

        assertEquals(2, options.size)
        assertEquals("opus-4-8", options[0].selection.modelId)
        assertEquals("Claude › Opus 4.8", options[0].label)
        assertEquals("sonnet-5", options[1].selection.modelId)
        assertEquals("Claude › Sonnet 5", options[1].label)
        options.forEach { assertTrue(it.enabled) }
    }

    @Test
    fun `unauthenticated source yields disabled options with sign in suffix`() {
        val source = ProviderModelSource(
            providerId = "anthropic",
            profileId = null,
            displayLabel = "Claude",
            authenticated = false,
            models = listOf(
                ModelEntry(id = "opus-4-8", displayName = "Opus 4.8", enabled = true),
            )
        )

        val options = buildChatOptions(listOf(source))

        assertEquals(1, options.size)
        assertEquals("Claude › Opus 4.8 (sign in)", options[0].label)
        assertFalse(options[0].enabled)
    }

    @Test
    fun `label uses displayName fallback to id when blank`() {
        val source = ProviderModelSource(
            providerId = "anthropic",
            profileId = null,
            displayLabel = "Claude",
            authenticated = true,
            models = listOf(
                ModelEntry(id = "model-with-blank-name", displayName = "", enabled = true),
            )
        )

        val options = buildChatOptions(listOf(source))

        assertEquals(1, options.size)
        assertEquals("Claude › model-with-blank-name", options[0].label)
    }

    @Test
    fun `selection carries providerId profileId and modelId`() {
        val source = ProviderModelSource(
            providerId = ProviderRegistry.OPENAI_COMPATIBLE_ID,
            profileId = "prof-a",
            displayLabel = "My Provider",
            authenticated = true,
            models = listOf(
                ModelEntry(id = "model-1", displayName = "Model 1", enabled = true, capability = "agentic"),
            )
        )

        val options = buildChatOptions(listOf(source))

        val selection = options[0].selection
        assertEquals(ProviderRegistry.OPENAI_COMPATIBLE_ID, selection.providerId)
        assertEquals("prof-a", selection.profileId)
        assertEquals("model-1", selection.modelId)
    }

    @Test
    fun `order preserved across sources and models`() {
        val sources = listOf(
            ProviderModelSource(
                providerId = "anthropic",
                profileId = null,
                displayLabel = "Claude",
                authenticated = true,
                models = listOf(
                    ModelEntry(id = "opus", displayName = "Opus", enabled = true),
                    ModelEntry(id = "sonnet", displayName = "Sonnet", enabled = true),
                )
            ),
            ProviderModelSource(
                providerId = ProviderRegistry.OPENAI_COMPATIBLE_ID,
                profileId = "prof",
                displayLabel = "Other",
                authenticated = true,
                models = listOf(
                    ModelEntry(id = "model-a", displayName = "Model A", enabled = true, capability = "agentic"),
                    ModelEntry(id = "model-b", displayName = "Model B", enabled = true, capability = "agentic"),
                )
            )
        )

        val options = buildChatOptions(sources)

        assertEquals(4, options.size)
        assertEquals("Claude › Opus", options[0].label)
        assertEquals("Claude › Sonnet", options[1].label)
        assertEquals("Other › Model A", options[2].label)
        assertEquals("Other › Model B", options[3].label)
    }

    @Test
    fun `empty sources list returns empty options`() {
        val options = buildChatOptions(emptyList())
        assertEquals(0, options.size)
    }
}
