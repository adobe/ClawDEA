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
package com.adobe.clawdea.chat

import com.adobe.clawdea.chat.ModelComboManager.Companion.rebuildActionFor
import com.adobe.clawdea.chat.ModelComboManager.RebuildAction
import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelComboManagerTest {

    private val catalog = listOf(
        ModelEntry(id = "claude-opus-4-8", displayName = "Claude Opus 4.8"),
        ModelEntry(id = "claude-sonnet-4-6", displayName = "Claude Sonnet 4.6"),
    )

    // ── rebuildActionFor (pure decision) ──────────────────────────────

    @Test
    fun `identical selection yields NONE`() {
        val sel = AgentSelection("anthropic", null, "claude-opus-4-8")
        assertEquals(RebuildAction.NONE, rebuildActionFor(sel, sel))
    }

    @Test
    fun `model-only difference on same provider yields RESTART`() {
        val current = AgentSelection("anthropic", null, "claude-opus-4-8")
        val picked = AgentSelection("anthropic", null, "claude-sonnet-4-6")
        assertEquals(RebuildAction.RESTART, rebuildActionFor(current, picked))
    }

    @Test
    fun `provider difference yields REBUILD_SESSION`() {
        // anthropic -> subscription: same backend kind (CLAUDE_CLI) but different provider id.
        val current = AgentSelection("anthropic", null, "claude-opus-4-8")
        val picked = AgentSelection("subscription", null, "claude-opus-4-8")
        assertEquals(RebuildAction.REBUILD_SESSION, rebuildActionFor(current, picked))
    }

    @Test
    fun `model-only difference on openai-compatible same profile yields REBUILD_SESSION`() {
        // The HTTP backend freezes the model in its immutable selection at construction, so a
        // model-only change on the same profile must rebuild (restart would keep the old model).
        val current = AgentSelection(ProviderRegistry.OPENAI_COMPATIBLE_ID, "p1", "model-a")
        val picked = AgentSelection(ProviderRegistry.OPENAI_COMPATIBLE_ID, "p1", "model-b")
        assertEquals(RebuildAction.REBUILD_SESSION, rebuildActionFor(current, picked))
    }

    @Test
    fun `profile difference on openai-compatible yields REBUILD_SESSION`() {
        val current = AgentSelection(ProviderRegistry.OPENAI_COMPATIBLE_ID, "p1", "model-x")
        val picked = AgentSelection(ProviderRegistry.OPENAI_COMPATIBLE_ID, "p2", "model-x")
        assertEquals(RebuildAction.REBUILD_SESSION, rebuildActionFor(current, picked))
    }

    @Test
    fun `backend-kind difference yields REBUILD_SESSION`() {
        // anthropic (CLAUDE_CLI) -> openai (CODEX_APP_SERVER).
        val current = AgentSelection("anthropic", null, "claude-opus-4-8")
        val picked = AgentSelection("openai", null, "gpt-5")
        assertEquals(RebuildAction.REBUILD_SESSION, rebuildActionFor(current, picked))
    }

    @Test
    fun `plain Default when no model observed`() {
        assertEquals("Default", ModelComboManager.defaultLabel(null, catalog))
        assertEquals("Default", ModelComboManager.defaultLabel("", catalog))
    }

    @Test
    fun `annotates with catalog display name minus Claude prefix`() {
        assertEquals("Default (Opus 4.8)", ModelComboManager.defaultLabel("claude-opus-4-8", catalog))
        assertEquals("Default (Sonnet 4.6)", ModelComboManager.defaultLabel("claude-sonnet-4-6", catalog))
    }

    @Test
    fun `falls back to prettified id when not in catalog`() {
        assertEquals("Default (Fable 5)", ModelComboManager.defaultLabel("claude-fable-5", catalog))
        // Bedrock-style id: strip provider prefix, prettify the claude- core.
        assertEquals("Default (Opus 4.7)", ModelComboManager.defaultLabel("us.anthropic.claude-opus-4-7", catalog))
    }

    @Test
    fun `prettyModelName formats id into name and dotted version`() {
        assertEquals("Opus 4.8", ModelComboManager.prettyModelName("claude-opus-4-8"))
        assertEquals("Sonnet 4.6", ModelComboManager.prettyModelName("claude-sonnet-4-6"))
        assertEquals("Haiku 4.5", ModelComboManager.prettyModelName("us.anthropic.claude-haiku-4-5"))
    }
}
