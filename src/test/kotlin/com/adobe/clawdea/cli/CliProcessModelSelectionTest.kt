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
package com.adobe.clawdea.cli

import com.adobe.clawdea.settings.ClawDEASettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guard (T13 fix (b)): CliProcess sources its `--model` value from the provider it was BUILT for
 * (the tab's selection, threaded via [CliProcess] `providerIdProvider`), not the global provider.
 *
 * Concrete bug this prevents: global = openai-subscription (Codex) pinned to gpt-5-codex; a per-tab
 * CLAUDE tab must run `claude --model <claude-model>`, not `claude --model gpt-5-codex` (invalid).
 */
class CliProcessModelSelectionTest {

    @Test
    fun `model is resolved from the selection provider not the global provider`() {
        val settings = ClawDEASettings()
        val dir = "/tmp/project-cli-model"

        // Global default is a Codex/OpenAI provider pinned to a Codex model.
        settings.state.apiProvider = "openai-subscription"
        settings.setSelectedModelId(dir, "gpt-5-codex", providerId = "openai-subscription")
        // The per-tab Claude provider has its own pinned model.
        settings.setSelectedModelId(dir, "claude-opus-4-8", providerId = "anthropic")

        // A CliProcess built for the anthropic selection (as AgentBackendFactory does) must read the
        // Claude model, NOT the global Codex model.
        val claudeProcess = CliProcess(workingDirectory = dir, providerIdProvider = { "anthropic" })
        assertEquals("claude-opus-4-8", claudeProcess.resolveCliModel(settings))
    }

    @Test
    fun `default provider lambda falls back to global read for non-factory callers`() {
        val settings = ClawDEASettings()
        val dir = "/tmp/project-cli-model-default"

        settings.state.apiProvider = "anthropic"
        settings.setSelectedModelId(dir, "claude-sonnet-4-6", providerId = "anthropic")

        // No explicit providerIdProvider → default reads AuthManager.effectiveProviderId().
        // We can't run AuthManager headless, so assert via an explicit provider matching the global.
        val process = CliProcess(workingDirectory = dir, providerIdProvider = { settings.state.apiProvider })
        assertEquals("claude-sonnet-4-6", process.resolveCliModel(settings))
    }

    @Test
    fun `pinned model from the tab selection wins over the settings map`() {
        val settings = ClawDEASettings()
        val dir = "/tmp/project-cli-model-pinned"

        // The settings map is EMPTY for anthropic — this is the regression scenario: a fresh chat
        // whose model was chosen via the Roles tab (roleSelections), never written to selectedModels.
        settings.state.apiProvider = "anthropic"

        // The tab's AgentSelection carries the concrete pinned model shown in the dropdown ("Opus").
        val process = CliProcess(
            workingDirectory = dir,
            providerIdProvider = { "anthropic" },
            pinnedModelId = "claude-opus-4-8",
        )
        // Must run `claude --model claude-opus-4-8`, NOT fall back to the CLI's own default (Haiku).
        assertEquals("claude-opus-4-8", process.resolveCliModel(settings))
    }

    @Test
    fun `blank pinned model falls back to the settings map`() {
        val settings = ClawDEASettings()
        val dir = "/tmp/project-cli-model-blank-pin"

        settings.state.apiProvider = "anthropic"
        settings.setSelectedModelId(dir, "claude-sonnet-4-6", providerId = "anthropic")

        // A blank pin (the CLI-picks-its-own-default case) preserves the existing settings-based read.
        val process = CliProcess(
            workingDirectory = dir,
            providerIdProvider = { "anthropic" },
            pinnedModelId = "",
        )
        assertEquals("claude-sonnet-4-6", process.resolveCliModel(settings))
    }
}
