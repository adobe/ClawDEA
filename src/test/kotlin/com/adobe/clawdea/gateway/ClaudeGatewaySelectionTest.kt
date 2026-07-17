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
package com.adobe.clawdea.gateway

import com.adobe.clawdea.provider.AgentSelection
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that the gateway routes completions based on the COMPLETIONS role selection,
 * not the global provider. Verifies that provider/profile/model come from AgentSelection.
 */
class ClaudeGatewaySelectionTest {

    @Test
    fun `selectPath routes to OPENAI_COMPATIBLE_API when provider is openai-compatible and ready`() {
        val path = ClaudeGateway.selectPath(
            providerId = com.adobe.clawdea.provider.ProviderRegistry.OPENAI_COMPATIBLE_ID,
            anthropicKeyPresent = false,
            bedrockDirectReady = false,
            openAiProfileReady = true,
        )
        assertEquals("Should route to OpenAI-compatible path", GatewayPath.OPENAI_COMPATIBLE_API, path)
    }

    @Test
    fun `selectPath routes to ANTHROPIC_API when provider is anthropic and key present`() {
        val path = ClaudeGateway.selectPath(
            providerId = "anthropic",
            anthropicKeyPresent = true,
            bedrockDirectReady = false,
            openAiProfileReady = false,
        )
        assertEquals("Should route to Anthropic path", GatewayPath.ANTHROPIC_API, path)
    }

    @Test
    fun `selectPath routes to BEDROCK_API when provider is bedrock and ready`() {
        val path = ClaudeGateway.selectPath(
            providerId = "bedrock",
            anthropicKeyPresent = false,
            bedrockDirectReady = true,
            openAiProfileReady = false,
        )
        assertEquals("Should route to Bedrock path", GatewayPath.BEDROCK_API, path)
    }

    @Test
    fun `selectPath falls back to CLAUDE_CLI when provider not ready`() {
        val path = ClaudeGateway.selectPath(
            providerId = com.adobe.clawdea.provider.ProviderRegistry.OPENAI_COMPATIBLE_ID,
            anthropicKeyPresent = false,
            bedrockDirectReady = false,
            openAiProfileReady = false,
        )
        assertEquals("Should fall back to CLI when selected provider not ready", GatewayPath.CLAUDE_CLI, path)
    }

    @Test
    fun `selectPath respects explicit openai-compatible selection even when anthropic key present`() {
        val path = ClaudeGateway.selectPath(
            providerId = com.adobe.clawdea.provider.ProviderRegistry.OPENAI_COMPATIBLE_ID,
            anthropicKeyPresent = true,
            bedrockDirectReady = false,
            openAiProfileReady = true,
        )
        assertEquals(
            "Should not fall through to Anthropic — explicit openai-compatible selection takes precedence",
            GatewayPath.OPENAI_COMPATIBLE_API,
            path,
        )
    }

    @Test
    fun `resolveCompletionsProfileId uses the selection profile`() {
        val sel = AgentSelection(
            com.adobe.clawdea.provider.ProviderRegistry.OPENAI_COMPATIBLE_ID,
            profileId = "p",
            modelId = "hosted_vllm/x",
        )
        assertEquals("p", ClaudeGateway.resolveCompletionsProfileId(sel))
    }

    @Test
    fun `resolveCompletionsProfileId returns blank when selection profile is null - no global fallback`() {
        val sel = AgentSelection("anthropic", profileId = null, modelId = "claude-haiku-4-5")
        assertEquals(
            "Null profile must yield blank (not the global active profile) — selection is authoritative",
            "",
            ClaudeGateway.resolveCompletionsProfileId(sel),
        )
    }

    @Test
    fun `resolveCompletionsModel prefers the selection modelId over request model`() {
        val sel = AgentSelection("anthropic", profileId = null, modelId = "claude-opus-4-8")
        assertEquals(
            "Non-blank selection modelId is authoritative",
            "claude-opus-4-8",
            ClaudeGateway.resolveCompletionsModel(sel, requestModel = "claude-haiku-4-5"),
        )
    }

    @Test
    fun `resolveCompletionsModel falls back to request model when selection modelId blank`() {
        val sel = AgentSelection("anthropic", profileId = null, modelId = "")
        assertEquals(
            "Blank selection modelId falls back to the request model (legacy migration source)",
            "claude-haiku-4-5",
            ClaudeGateway.resolveCompletionsModel(sel, requestModel = "claude-haiku-4-5"),
        )
    }

    // --- COMPLETIONS role applies only to completion requests, not to shared callers (quick-fix actions) ---

    @Test
    fun `non-completion request keeps the global effective provider, ignoring the Completions role`() {
        assertEquals(
            "A quick-fix action must route by the global provider, not the Completions role selection",
            "anthropic",
            ClaudeGateway.resolveGatewayProviderId(
                applyCompletionsRole = false,
                completionsProviderId = "openai-compatible",
                effectiveProviderId = "anthropic",
            ),
        )
    }

    @Test
    fun `completion request routes through the Completions role provider`() {
        assertEquals(
            "An inline completion opts in and routes by the Completions role selection",
            "openai-compatible",
            ClaudeGateway.resolveGatewayProviderId(
                applyCompletionsRole = true,
                completionsProviderId = "openai-compatible",
                effectiveProviderId = "anthropic",
            ),
        )
    }

    @Test
    fun `non-completion request keeps its own model, not the Completions role model`() {
        val completionsSel = AgentSelection("anthropic", profileId = null, modelId = "claude-opus-4-8")
        assertEquals(
            "A quick-fix action must keep its own pinned model",
            "action-pinned-model",
            ClaudeGateway.resolveGatewayModel(
                applyCompletionsRole = false,
                selection = completionsSel,
                requestModel = "action-pinned-model",
            ),
        )
    }

    @Test
    fun `completion request applies the Completions role model override`() {
        val completionsSel = AgentSelection("anthropic", profileId = null, modelId = "claude-opus-4-8")
        assertEquals(
            "An inline completion uses the Completions role's model",
            "claude-opus-4-8",
            ClaudeGateway.resolveGatewayModel(
                applyCompletionsRole = true,
                selection = completionsSel,
                requestModel = "some-request-model",
            ),
        )
    }
}
