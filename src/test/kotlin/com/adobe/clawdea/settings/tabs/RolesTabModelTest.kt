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

import com.adobe.clawdea.chat.ProviderModelSource
import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.AgentRole
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RolesTabModelTest {

    @Test
    fun `WIKI role warns when a completion_only model is selected`() {
        val selection = AgentSelection(
            providerId = ProviderRegistry.OPENAI_COMPATIBLE_ID,
            profileId = "prof1",
            modelId = "completion-model"
        )
        val modelEntry = ModelEntry(
            id = "completion-model",
            capability = "completion_only",
            enabled = true
        )

        val hasWarning = computeCapabilityWarning(AgentRole.WIKI, modelEntry)

        assertTrue("WIKI with completion_only model should warn", hasWarning)
    }

    @Test
    fun `WIKI role does not warn when an agentic model is selected`() {
        val modelEntry = ModelEntry(
            id = "agentic-model",
            capability = "agentic",
            enabled = true
        )

        val hasWarning = computeCapabilityWarning(AgentRole.WIKI, modelEntry)

        assertFalse("WIKI with agentic model should not warn", hasWarning)
    }

    @Test
    fun `WIKI role does not warn when capability is unknown`() {
        val modelEntry = ModelEntry(
            id = "unknown-model",
            capability = "unknown",
            enabled = true
        )

        val hasWarning = computeCapabilityWarning(AgentRole.WIKI, modelEntry)

        assertFalse("WIKI with unknown capability should not warn (benefit of doubt)", hasWarning)
    }

    @Test
    fun `CHAT_DEFAULT role never warns even for completion_only models`() {
        val modelEntry = ModelEntry(
            id = "completion-model",
            capability = "completion_only",
            enabled = true
        )

        val hasWarning = computeCapabilityWarning(AgentRole.CHAT_DEFAULT, modelEntry)

        assertFalse("CHAT_DEFAULT should never warn", hasWarning)
    }

    @Test
    fun `COMPLETIONS role never warns even for completion_only models`() {
        val modelEntry = ModelEntry(
            id = "completion-model",
            capability = "completion_only",
            enabled = true
        )

        val hasWarning = computeCapabilityWarning(AgentRole.COMPLETIONS, modelEntry)

        assertFalse("COMPLETIONS should never warn", hasWarning)
    }

    @Test
    fun `buildRoleOptions includes completion_only models for role pickers`() {
        val source = ProviderModelSource(
            providerId = ProviderRegistry.OPENAI_COMPATIBLE_ID,
            profileId = "prof1",
            displayLabel = "My Provider",
            authenticated = true,
            models = listOf(
                ModelEntry(id = "agentic-1", displayName = "Agentic Model", enabled = true, capability = "agentic"),
                ModelEntry(id = "completion-1", displayName = "Completion Model", enabled = true, capability = "completion_only"),
                ModelEntry(id = "disabled-model", displayName = "Disabled", enabled = false, capability = "agentic"),
            )
        )

        val options = buildRoleOptions(listOf(source))

        // Role pickers show both agentic AND completion_only enabled models (unlike chat dropdown which filters to agentic only)
        assertEquals(2, options.size)
        assertEquals("agentic-1", options[0].selection.modelId)
        assertEquals("completion-1", options[1].selection.modelId)
    }

    @Test
    fun `buildRoleOptions for non-openai-compatible providers passes all enabled models`() {
        val source = ProviderModelSource(
            providerId = "anthropic",
            profileId = null,
            displayLabel = "Claude",
            authenticated = true,
            models = listOf(
                ModelEntry(id = "opus", displayName = "Opus", enabled = true, capability = "unknown"),
                ModelEntry(id = "sonnet", displayName = "Sonnet", enabled = true, capability = "agentic"),
                ModelEntry(id = "haiku", displayName = "Haiku", enabled = false, capability = "agentic"),
            )
        )

        val options = buildRoleOptions(listOf(source))

        assertEquals(2, options.size)
        assertEquals("opus", options[0].selection.modelId)
        assertEquals("sonnet", options[1].selection.modelId)
    }

    @Test
    fun `buildRoleOptions respects authentication state`() {
        val source = ProviderModelSource(
            providerId = "anthropic",
            profileId = null,
            displayLabel = "Claude",
            authenticated = false,
            models = listOf(
                ModelEntry(id = "opus", displayName = "Opus", enabled = true),
            )
        )

        val options = buildRoleOptions(listOf(source))

        assertEquals(1, options.size)
        assertEquals("Claude › Opus (sign in)", options[0].label)
        assertFalse(options[0].enabled)
    }
}
