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
package com.adobe.clawdea.provider

import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.RoleDefaultsResolver.ProviderAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoleDefaultsResolverTest {

    private fun models(vararg ids: String) = ids.map { ModelEntry(id = it, displayName = it) }

    private fun claude(id: String, vararg modelIds: String) =
        ProviderAvailability(id, null, BackendKind.CLAUDE_CLI, models(*modelIds))

    @Test fun `no availability yields null so caller keeps legacy fallback`() {
        assertNull(RoleDefaultsResolver.resolve(emptyList()))
    }

    @Test fun `anthropic picks latest opus for chat, latest haiku for wiki and completions`() {
        val out = RoleDefaultsResolver.resolve(
            listOf(claude("anthropic", "claude-opus-4-8", "claude-sonnet-5", "claude-haiku-4-5")),
        )!!
        assertEquals(AgentSelection("anthropic", null, "claude-opus-4-8"), out[AgentRole.CHAT_DEFAULT])
        assertEquals(AgentSelection("anthropic", null, "claude-haiku-4-5"), out[AgentRole.WIKI])
        assertEquals(AgentSelection("anthropic", null, "claude-haiku-4-5"), out[AgentRole.COMPLETIONS])
    }

    @Test fun `bedrock inference-profile ids match opus and haiku families`() {
        val out = RoleDefaultsResolver.resolve(
            listOf(
                claude(
                    "bedrock",
                    "us.anthropic.claude-opus-4-7",
                    "us.anthropic.claude-sonnet-4-6",
                    "us.anthropic.claude-haiku-4-5-20251001-v1:0",
                ),
            ),
        )!!
        assertEquals("us.anthropic.claude-opus-4-7", out[AgentRole.CHAT_DEFAULT]!!.modelId)
        assertEquals("us.anthropic.claude-haiku-4-5-20251001-v1:0", out[AgentRole.WIKI]!!.modelId)
    }

    @Test fun `subscription wins over bedrock when both authenticated`() {
        val out = RoleDefaultsResolver.resolve(
            listOf(
                claude("bedrock", "us.anthropic.claude-opus-4-7", "us.anthropic.claude-haiku-4-5-20251001-v1:0"),
                claude("subscription", "claude-opus-4-8", "claude-haiku-4-5"),
            ),
        )!!
        assertEquals("subscription", out[AgentRole.CHAT_DEFAULT]!!.providerId)
        assertEquals("claude-opus-4-8", out[AgentRole.CHAT_DEFAULT]!!.modelId)
    }

    @Test fun `claude without opus or haiku falls back to sonnet`() {
        val out = RoleDefaultsResolver.resolve(listOf(claude("anthropic", "claude-sonnet-5")))!!
        assertEquals("claude-sonnet-5", out[AgentRole.CHAT_DEFAULT]!!.modelId)
        assertEquals("claude-sonnet-5", out[AgentRole.WIKI]!!.modelId)
    }

    @Test fun `codex picks terra for chat and luna for wiki and completions`() {
        val out = RoleDefaultsResolver.resolve(
            listOf(
                ProviderAvailability(
                    "openai-subscription", null, BackendKind.CODEX_APP_SERVER,
                    models("gpt-5.4-terra", "gpt-5.4-sol", "gpt-5.4-luna"),
                ),
            ),
        )!!
        assertEquals("gpt-5.4-terra", out[AgentRole.CHAT_DEFAULT]!!.modelId)
        assertEquals("gpt-5.4-luna", out[AgentRole.WIKI]!!.modelId)
        assertEquals("gpt-5.4-luna", out[AgentRole.COMPLETIONS]!!.modelId)
    }

    @Test fun `codex with empty catalog defaults to the blank account model`() {
        val out = RoleDefaultsResolver.resolve(
            listOf(ProviderAvailability("openai-subscription", null, BackendKind.CODEX_APP_SERVER, emptyList())),
        )!!
        assertEquals("openai-subscription", out[AgentRole.CHAT_DEFAULT]!!.providerId)
        assertEquals("", out[AgentRole.CHAT_DEFAULT]!!.modelId)
    }

    @Test fun `claude is preferred over codex when both available`() {
        val out = RoleDefaultsResolver.resolve(
            listOf(
                ProviderAvailability("openai-subscription", null, BackendKind.CODEX_APP_SERVER, models("gpt-5.4-terra")),
                claude("subscription", "claude-opus-4-8", "claude-haiku-4-5"),
            ),
        )!!
        assertEquals("subscription", out[AgentRole.CHAT_DEFAULT]!!.providerId)
    }

    @Test fun `openai-compatible prefers an agentic model for every role`() {
        val agentic = ModelEntry(id = "big-model", displayName = "Big", capability = "agentic")
        val completionOnly = ModelEntry(id = "small-model", displayName = "Small", capability = "completion_only")
        val out = RoleDefaultsResolver.resolve(
            listOf(
                ProviderAvailability(
                    ProviderRegistry.OPENAI_COMPATIBLE_ID, "p1", BackendKind.OPENAI_COMPATIBLE_HTTP,
                    listOf(completionOnly, agentic),
                ),
            ),
        )!!
        assertEquals(
            AgentSelection(ProviderRegistry.OPENAI_COMPATIBLE_ID, "p1", "big-model"),
            out[AgentRole.CHAT_DEFAULT],
        )
        assertEquals("big-model", out[AgentRole.WIKI]!!.modelId)
    }
}
