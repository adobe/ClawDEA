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
