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

import com.adobe.clawdea.cli.backend.AgentBackendFactory
import com.adobe.clawdea.cli.backend.OpenAiCompatibleAgentBackend
import com.adobe.clawdea.cli.backend.ProcessAgentBackend
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.settings.ClawDEASettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the provider→backend classification that drives ChatPanel's provider-switch handling.
 * Any exact [BackendKind] change must rebuild the tab's ChatSession because a bridge's process is
 * fixed at construction.
 */
class CliBridgeBackendSelectionTest {

    @Test
    fun `openai providers are codex-backed`() {
        assertTrue(CliBridge.isCodexProvider("openai"))
        assertTrue(CliBridge.isCodexProvider("openai-subscription"))
    }

    @Test
    fun `claude providers are not codex-backed`() {
        assertFalse(CliBridge.isCodexProvider("anthropic"))
        assertFalse(CliBridge.isCodexProvider("bedrock"))
        assertFalse(CliBridge.isCodexProvider("vertex"))
        assertFalse(CliBridge.isCodexProvider("subscription"))
    }

    @Test
    fun `unknown provider is treated as claude-backed`() {
        assertFalse(CliBridge.isCodexProvider("something-else"))
        assertFalse(CliBridge.isCodexProvider(""))
    }

    @Test
    fun `HTTP provider never falls through to Claude process`() {
        // Inject a fresh (empty) settings instance so the factory runs fully headless — no platform
        // Application / PasswordSafe required. With no profile configured the HTTP branch degrades to
        // an OpenAiCompatibleAgentBackend carrying a readiness error, but it is STILL the HTTP backend
        // (correct kind) and is NEVER a ProcessAgentBackend driving the `claude`/`codex` CLI.
        val backend = AgentBackendFactory.create("openai-compatible", settings = ClawDEASettings())
        assertEquals(BackendKind.OPENAI_COMPATIBLE_HTTP, backend.backendKind)
        assertTrue(backend is OpenAiCompatibleAgentBackend)
        assertFalse(backend is ProcessAgentBackend)
        assertFalse(CliBridge.isCodexProvider("openai-compatible"))
        assertFalse(CliBridge.requiresBackendRebuild(BackendKind.OPENAI_COMPATIBLE_HTTP, "openai-compatible"))
    }

    @Test
    fun `a codex to claude switch is a backend change`() {
        assertTrue(
            CliBridge.requiresBackendRebuild(BackendKind.CODEX_APP_SERVER, "bedrock"),
        )
    }

    @Test
    fun `a claude to HTTP switch is a backend change`() {
        assertTrue(
            CliBridge.requiresBackendRebuild(BackendKind.CLAUDE_CLI, "openai-compatible"),
        )
    }

    @Test
    fun `an HTTP to claude switch is a backend change`() {
        assertTrue(
            CliBridge.requiresBackendRebuild(BackendKind.OPENAI_COMPATIBLE_HTTP, "anthropic"),
        )
    }

    @Test
    fun `a same-backend provider switch is not a backend change`() {
        assertFalse(
            CliBridge.requiresBackendRebuild(BackendKind.CLAUDE_CLI, "bedrock"),
        )
        assertFalse(
            CliBridge.requiresBackendRebuild(BackendKind.CODEX_APP_SERVER, "openai-subscription"),
        )
        assertFalse(
            CliBridge.requiresBackendRebuild(BackendKind.OPENAI_COMPATIBLE_HTTP, "openai-compatible"),
        )
    }

    @Test
    fun `HTTP backend reads activeProfileId not providerId for profile and credential lookup`() {
        // BUG 2 fix: the factory resolves by activeOpenAiCompatibleProfileId, not "openai-compatible".
        // With no active profile set, the backend degrades to error (not crash).
        val settings = ClawDEASettings()
        settings.state.activeOpenAiCompatibleProfileId = "" // blank

        val backend = AgentBackendFactory.create("openai-compatible", settings = settings)

        assertEquals(BackendKind.OPENAI_COMPATIBLE_HTTP, backend.backendKind)
        assertTrue(backend is OpenAiCompatibleAgentBackend)

        // The readiness error is exposed via start() → readEvent()
        backend.start(resumeSessionId = null, skills = emptyList())
        val event = backend.readEvent()
        assertTrue(event is com.adobe.clawdea.cli.CliEvent.Result)
        val result = event as com.adobe.clawdea.cli.CliEvent.Result
        assertTrue(result.isError)
        assertTrue(result.text.contains("No OpenAI-compatible profile selected"))
    }
}
