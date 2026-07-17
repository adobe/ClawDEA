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
}
