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
package com.adobe.clawdea.completions

import com.adobe.clawdea.completions.ClawDEACompletionProvider.Companion.isProviderCompletionEnabled
import com.adobe.clawdea.provider.ProviderRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the gating logic in ClawDEACompletionProvider.isProviderCompletionEnabled.
 * This pure function decides whether a provider is ready to serve completions based on:
 *   - supportsInlineCompletions flag
 *   - providerConfigured flag
 *   - selectedModelId (required non-blank for openai-compatible, allowed blank for Claude providers)
 *
 * These tests verify that the gating prevents regressions for existing Claude providers
 * (anthropic, bedrock, subscription, vertex) while correctly gating openai-compatible.
 */
class ClawDEACompletionProviderGatingTest {

    @Test
    fun `anthropic provider with key and selected model enables completions`() {
        // Anthropic users with a configured key and selected model should get completions.
        val result = isProviderCompletionEnabled(
            providerId = "anthropic",
            supportsInlineCompletions = true,
            providerConfigured = true,
            selectedModelId = "claude-sonnet-4-6",
        )
        assertTrue("Anthropic with key + model should enable completions", result)
    }

    @Test
    fun `anthropic provider with key and blank model enables completions`() {
        // Anthropic users who never selected a model (relying on CLI defaults) should still get completions.
        // The non-blank model requirement applies ONLY to openai-compatible.
        val result = isProviderCompletionEnabled(
            providerId = "anthropic",
            supportsInlineCompletions = true,
            providerConfigured = true,
            selectedModelId = "",
        )
        assertTrue("Anthropic with key + blank model should enable completions (CLI fallback)", result)
    }

    @Test
    fun `openai-compatible with profile but no selected model disables completions`() {
        // OpenAI-compatible requires an explicit model selection.
        // Unlike Claude providers, openai-compatible has no fallback to CLI defaults.
        val result = isProviderCompletionEnabled(
            providerId = ProviderRegistry.OPENAI_COMPATIBLE_ID,
            supportsInlineCompletions = true,
            providerConfigured = true,
            selectedModelId = "",
        )
        assertFalse("OpenAI-compatible with profile but no model should disable completions", result)
    }

    @Test
    fun `openai-compatible with profile and selected model enables completions`() {
        // OpenAI-compatible with both profile and model should enable completions.
        val result = isProviderCompletionEnabled(
            providerId = ProviderRegistry.OPENAI_COMPATIBLE_ID,
            supportsInlineCompletions = true,
            providerConfigured = true,
            selectedModelId = "gpt-4o",
        )
        assertTrue("OpenAI-compatible with profile + model should enable completions", result)
    }

    @Test
    fun `openai-compatible with no profile disables completions`() {
        // OpenAI-compatible without a profile (not configured) should disable completions.
        val result = isProviderCompletionEnabled(
            providerId = ProviderRegistry.OPENAI_COMPATIBLE_ID,
            supportsInlineCompletions = true,
            providerConfigured = false,
            selectedModelId = "gpt-4o",
        )
        assertFalse("OpenAI-compatible with no profile should disable completions", result)
    }

    @Test
    fun `provider without inline completion support disables completions`() {
        // Providers that don't support inline completions (e.g. codex via app-server)
        // should disable completions regardless of other flags.
        val result = isProviderCompletionEnabled(
            providerId = "openai",
            supportsInlineCompletions = false,
            providerConfigured = true,
            selectedModelId = "gpt-4o",
        )
        assertFalse("Provider without inline completion support should disable completions", result)
    }

    @Test
    fun `unconfigured provider disables completions`() {
        // Unconfigured providers (no credentials, no profile) should disable completions.
        val result = isProviderCompletionEnabled(
            providerId = "anthropic",
            supportsInlineCompletions = true,
            providerConfigured = false,
            selectedModelId = "claude-sonnet-4-6",
        )
        assertFalse("Unconfigured provider should disable completions", result)
    }

    @Test
    fun `bedrock provider with environment auth and blank model enables completions`() {
        // Bedrock (another Claude provider) should behave like anthropic — allow blank model.
        val result = isProviderCompletionEnabled(
            providerId = "bedrock",
            supportsInlineCompletions = true,
            providerConfigured = true,
            selectedModelId = "",
        )
        assertTrue("Bedrock with environment auth + blank model should enable completions", result)
    }

    @Test
    fun `subscription provider with login and blank model enables completions`() {
        // Subscription (another Claude provider) should behave like anthropic — allow blank model.
        val result = isProviderCompletionEnabled(
            providerId = "subscription",
            supportsInlineCompletions = true,
            providerConfigured = true,
            selectedModelId = "",
        )
        assertTrue("Subscription with login + blank model should enable completions", result)
    }
}
