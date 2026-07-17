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
import com.adobe.clawdea.settings.ClawDEASettings
import org.junit.Assert.*
import org.junit.Test
class RoleSelectionStoreTest {
    private fun settings() = ClawDEASettings() // plain instance; State is a POJO
    @Test fun `migration seeds all roles from legacy apiProvider and selected model`() {
        val s = settings()
        s.state.apiProvider = "anthropic"
        s.state.selectedModels["anthropic|"] = "claude-opus-4-8" // key = providerId|workingDir(blank global)
        val store = RoleSelectionStore(s)
        store.migrateFromLegacyIfNeeded()
        val chat = store.get(AgentRole.CHAT_DEFAULT)
        assertEquals("anthropic", chat.providerId)
        assertEquals(AgentSelection("anthropic", null, "claude-opus-4-8"), store.get(AgentRole.WIKI))
        assertTrue(s.state.roleSelectionsMigrated)
        // Idempotency: a second migration with changed legacy state must be a no-op.
        s.state.apiProvider = "openai"
        s.state.selectedModels["openai|"] = "gpt-4"
        store.migrateFromLegacyIfNeeded()
        assertEquals("anthropic", store.get(AgentRole.CHAT_DEFAULT).providerId) // unchanged
    }
    @Test fun `set then get round-trips a selection`() {
        val store = RoleSelectionStore(settings())
        val sel = AgentSelection("openai-compatible", "apc", "hosted_vllm/x")
        store.set(AgentRole.COMPLETIONS, sel)
        assertEquals(sel, store.get(AgentRole.COMPLETIONS))
    }
    @Test fun `get returns anthropic default when unset with blank legacy provider`() {
        assertEquals("anthropic", RoleSelectionStore(settings()).get(AgentRole.CHAT_DEFAULT).providerId)
    }
    @Test fun `default openai-compatible selection snapshots the active profile`() {
        val s = settings()
        s.state.apiProvider = ProviderRegistry.OPENAI_COMPATIBLE_ID
        s.state.activeOpenAiCompatibleProfileId = "p1"
        val sel = RoleSelectionStore(s).get(AgentRole.COMPLETIONS)
        assertEquals(ProviderRegistry.OPENAI_COMPATIBLE_ID, sel.providerId)
        assertEquals("selection captures the global active profile so it is self-contained", "p1", sel.profileId)
    }
    @Test fun `default openai-compatible selection has null profile when no active profile`() {
        val s = settings()
        s.state.apiProvider = ProviderRegistry.OPENAI_COMPATIBLE_ID
        s.state.activeOpenAiCompatibleProfileId = ""
        assertNull(RoleSelectionStore(s).get(AgentRole.COMPLETIONS).profileId)
    }
    @Test fun `default anthropic selection keeps null profile`() {
        val s = settings()
        s.state.apiProvider = "anthropic"
        s.state.activeOpenAiCompatibleProfileId = "p1" // must be ignored for non-openai-compatible
        assertNull(RoleSelectionStore(s).get(AgentRole.CHAT_DEFAULT).profileId)
    }
}
