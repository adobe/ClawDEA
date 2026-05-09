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
// src/test/kotlin/com/adobe/clawdea/gateway/ClaudeGatewayTest.kt
package com.adobe.clawdea.gateway

import org.junit.Assert.*
import org.junit.Test

class ClaudeGatewayTest {

    @Test
    fun `shouldUseBareMode false when setting disabled`() {
        assertFalse(
            ClaudeGateway.shouldUseBareMode(
                providerId = "anthropic",
                providerConfigured = true,
                settingEnabled = false,
            ),
        )
    }

    @Test
    fun `shouldUseBareMode false when provider not configured`() {
        assertFalse(
            "API key not present even though setting is on — falls back to OAuth, --bare would break auth",
            ClaudeGateway.shouldUseBareMode(
                providerId = "anthropic",
                providerConfigured = false,
                settingEnabled = true,
            ),
        )
    }

    @Test
    fun `shouldUseBareMode false for unknown provider`() {
        assertFalse(
            "vertex/foundry/etc are not on the BARE_MODE_SAFE_PROVIDERS allowlist",
            ClaudeGateway.shouldUseBareMode(
                providerId = "vertex",
                providerConfigured = true,
                settingEnabled = true,
            ),
        )
    }

    @Test
    fun `shouldUseBareMode true for anthropic with API key and setting on`() {
        assertTrue(
            ClaudeGateway.shouldUseBareMode(
                providerId = "anthropic",
                providerConfigured = true,
                settingEnabled = true,
            ),
        )
    }

    @Test
    fun `shouldUseBareMode true for bedrock with credentials and setting on`() {
        assertTrue(
            ClaudeGateway.shouldUseBareMode(
                providerId = "bedrock",
                providerConfigured = true,
                settingEnabled = true,
            ),
        )
    }

    @Test
    fun `buildRequestBody produces valid JSON`() {
        val request = GatewayRequest(
            model = "claude-haiku-4-5",
            maxTokens = 256,
            systemPrompt = "You are a code assistant.",
            userMessage = "Complete this code",
            timeoutSeconds = 5,
        )
        val json = ClaudeGateway.buildRequestBody(request)

        assertTrue("Should contain model", json.contains("\"model\":\"claude-haiku-4-5\""))
        assertTrue("Should contain max_tokens", json.contains("\"max_tokens\":256"))
        assertTrue("Should contain stream", json.contains("\"stream\":true"))
        assertTrue("Should contain system", json.contains("\"system\":\"You are a code assistant.\""))
        assertTrue("Should contain messages", json.contains("\"messages\""))
        assertTrue("Should contain user role", json.contains("\"role\":\"user\""))
        assertTrue("Should contain content", json.contains("\"content\":\"Complete this code\""))
    }

    @Test
    fun `buildRequestBody omits system when null`() {
        val request = GatewayRequest(
            model = "claude-haiku-4-5",
            maxTokens = 256,
            systemPrompt = null,
            userMessage = "Hello",
        )
        val json = ClaudeGateway.buildRequestBody(request)

        assertFalse("Should not contain system key", json.contains("\"system\""))
    }

    @Test
    fun `buildRequestBody escapes special characters in content`() {
        val request = GatewayRequest(
            model = "claude-haiku-4-5",
            maxTokens = 256,
            systemPrompt = null,
            userMessage = "line1\nline2\t\"quoted\"",
        )
        val json = ClaudeGateway.buildRequestBody(request)

        assertTrue("Should escape newline", json.contains("\\n"))
        assertTrue("Should escape tab", json.contains("\\t"))
        assertTrue("Should escape quotes", json.contains("\\\"quoted\\\""))
    }
}
