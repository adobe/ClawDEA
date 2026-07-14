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
package com.adobe.clawdea.settings

import com.adobe.clawdea.gateway.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClawDEASettingsSelectedModelTest {

    @Test
    fun `selected model is partitioned by provider`() {
        val settings = ClawDEASettings()
        val dir = "/tmp/project-a"

        settings.state.apiProvider = "bedrock"
        settings.setSelectedModelId(dir, "us.anthropic.claude-opus-4-7")
        assertEquals("us.anthropic.claude-opus-4-7", settings.getSelectedModelId(dir))

        settings.state.apiProvider = "subscription"
        assertEquals("", settings.getSelectedModelId(dir))

        settings.setSelectedModelId(dir, "claude-opus-4-7")
        assertEquals("claude-opus-4-7", settings.getSelectedModelId(dir))

        settings.state.apiProvider = "bedrock"
        assertEquals("us.anthropic.claude-opus-4-7", settings.getSelectedModelId(dir))
    }

    @Test
    fun `selected model is dropped when not in current catalog`() {
        val settings = ClawDEASettings()
        val dir = "/tmp/project-b"

        settings.state.apiProvider = "anthropic"
        settings.state.modelCatalogs["anthropic"] = mutableListOf(
            ModelEntry(id = "claude-opus-4-7", displayName = "Claude Opus 4.7"),
        )
        settings.setSelectedModelId(dir, "unknown-model-id")

        assertEquals("", settings.getSelectedModelId(dir))
    }

    @Test
    fun `getCliModelId falls back to first subscription catalog entry when blank`() {
        val settings = ClawDEASettings()
        val dir = "/tmp/project-d"

        settings.state.apiProvider = "subscription"
        settings.state.modelCatalogs["subscription"] = mutableListOf(
            ModelEntry(id = "claude-opus-4-7",   displayName = "Claude Opus 4.7"),
            ModelEntry(id = "claude-sonnet-4-6", displayName = "Claude Sonnet 4.6"),
        )

        assertEquals("claude-opus-4-7", settings.getCliModelId(dir))

        settings.setSelectedModelId(dir, "claude-sonnet-4-6")
        assertEquals("claude-sonnet-4-6", settings.getCliModelId(dir))
    }

    @Test
    fun `getCliModelId returns blank for non-subscription providers when unset`() {
        val settings = ClawDEASettings()
        val dir = "/tmp/project-e"

        settings.state.apiProvider = "anthropic"
        assertEquals("", settings.getCliModelId(dir))

        settings.state.apiProvider = "bedrock"
        assertEquals("", settings.getCliModelId(dir))

        settings.state.apiProvider = "vertex"
        assertEquals("", settings.getCliModelId(dir))
    }

    @Test
    fun `mergeMissingModelCatalogs seeds providers missing from persisted state`() {
        // Simulate an install whose persisted modelCatalogs predates newer providers
        // (e.g. openai / openai-subscription) — the dropdown must not be left empty.
        val legacy: MutableMap<String, MutableList<ModelEntry>> = mutableMapOf(
            "anthropic" to mutableListOf(ModelEntry(id = "claude-opus-4-7", displayName = "Claude Opus 4.7")),
        )

        ClawDEASettings.mergeMissingModelCatalogs(legacy)

        assertEquals(
            "user-customized provider is preserved untouched",
            mutableListOf(ModelEntry(id = "claude-opus-4-7", displayName = "Claude Opus 4.7")),
            legacy["anthropic"],
        )
        assertTrue("openai catalog seeded", legacy["openai"]?.isNotEmpty() == true)
        // openai-subscription is seeded as a present-but-empty catalog: the dropdown renders the
        // working "Default (account model)" entry; account-specific models come from the probe.
        assertTrue("openai-subscription catalog present", legacy.containsKey("openai-subscription"))
        assertTrue("openai-subscription defers to account default", legacy["openai-subscription"]?.isEmpty() == true)
    }

    @Test
    fun `mergeMissingModelCatalogs scrubs the stale API-model seed from openai-subscription`() {
        // An earlier build seeded openai-subscription from the API-key catalog (gpt-5-*), which
        // 400s on a ChatGPT account. The migration must reset that exact seed to the default.
        val stale: MutableMap<String, MutableList<ModelEntry>> = mutableMapOf(
            "openai-subscription" to com.adobe.clawdea.gateway.DEFAULT_OPENAI_CATALOG.toMutableList(),
        )

        ClawDEASettings.mergeMissingModelCatalogs(stale)

        assertTrue("stale gpt-5 seed scrubbed", stale["openai-subscription"]?.isEmpty() == true)
    }

    @Test
    fun `mergeMissingModelCatalogs preserves a user-customized openai-subscription catalog`() {
        val custom = mutableListOf(ModelEntry(id = "gpt-5.6-sol", displayName = "GPT-5.6"))
        val map: MutableMap<String, MutableList<ModelEntry>> = mutableMapOf(
            "openai-subscription" to custom,
        )

        ClawDEASettings.mergeMissingModelCatalogs(map)

        assertEquals("a customized catalog is not scrubbed", custom, map["openai-subscription"])
    }

    @Test
    fun `setSelectedModelId with blank removes the entry`() {
        val settings = ClawDEASettings()
        val dir = "/tmp/project-c"

        settings.state.apiProvider = "anthropic"
        settings.setSelectedModelId(dir, "claude-opus-4-7")
        assertEquals("claude-opus-4-7", settings.getSelectedModelId(dir))

        settings.setSelectedModelId(dir, "")
        assertEquals("", settings.getSelectedModelId(dir))
    }
}
