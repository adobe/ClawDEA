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

import org.junit.Assert.assertEquals
import org.junit.Test

class ClaudeGatewayOpenAiPathTest {

    @Test
    fun `selected generic profile uses direct compatible API`() {
        assertEquals(
            GatewayPath.OPENAI_COMPATIBLE_API,
            ClaudeGateway.selectPath(
                providerId = "openai-compatible",
                anthropicKeyPresent = true,
                bedrockDirectReady = false,
                openAiProfileReady = true,
            ),
        )
    }

    @Test
    fun `anthropic provider with key uses direct anthropic API`() {
        assertEquals(
            GatewayPath.ANTHROPIC_API,
            ClaudeGateway.selectPath(
                providerId = "anthropic",
                anthropicKeyPresent = true,
                bedrockDirectReady = false,
                openAiProfileReady = false,
            ),
        )
    }

    @Test
    fun `anthropic provider without key falls back to CLI`() {
        assertEquals(
            GatewayPath.CLAUDE_CLI,
            ClaudeGateway.selectPath(
                providerId = "anthropic",
                anthropicKeyPresent = false,
                bedrockDirectReady = false,
                openAiProfileReady = false,
            ),
        )
    }

    @Test
    fun `bedrock provider with credentials uses direct bedrock API`() {
        assertEquals(
            GatewayPath.BEDROCK_API,
            ClaudeGateway.selectPath(
                providerId = "bedrock",
                anthropicKeyPresent = true,
                bedrockDirectReady = true,
                openAiProfileReady = false,
            ),
        )
    }

    @Test
    fun `bedrock provider without credentials falls back to CLI`() {
        assertEquals(
            GatewayPath.CLAUDE_CLI,
            ClaudeGateway.selectPath(
                providerId = "bedrock",
                anthropicKeyPresent = false,
                bedrockDirectReady = false,
                openAiProfileReady = false,
            ),
        )
    }

    @Test
    fun `openai-compatible without profile falls back to CLI`() {
        assertEquals(
            GatewayPath.CLAUDE_CLI,
            ClaudeGateway.selectPath(
                providerId = "openai-compatible",
                anthropicKeyPresent = false,
                bedrockDirectReady = false,
                openAiProfileReady = false,
            ),
        )
    }

    @Test
    fun `subscription provider always uses CLI`() {
        assertEquals(
            GatewayPath.CLAUDE_CLI,
            ClaudeGateway.selectPath(
                providerId = "subscription",
                anthropicKeyPresent = true,
                bedrockDirectReady = false,
                openAiProfileReady = false,
            ),
        )
    }

    @Test
    fun `vertex provider always uses CLI`() {
        assertEquals(
            GatewayPath.CLAUDE_CLI,
            ClaudeGateway.selectPath(
                providerId = "vertex",
                anthropicKeyPresent = false,
                bedrockDirectReady = false,
                openAiProfileReady = false,
            ),
        )
    }

    @Test
    fun `codex provider always uses CLI`() {
        assertEquals(
            GatewayPath.CLAUDE_CLI,
            ClaudeGateway.selectPath(
                providerId = "openai",
                anthropicKeyPresent = true,
                bedrockDirectReady = false,
                openAiProfileReady = false,
            ),
        )
    }
}
