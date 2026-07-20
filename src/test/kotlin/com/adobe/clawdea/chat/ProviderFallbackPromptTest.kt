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

import com.adobe.clawdea.chat.session.SessionOrigin
import com.adobe.clawdea.provider.BackendKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFallbackPromptTest {

    @Test
    fun `same-provider resume never requires confirmation`() {
        assertFalse(ProviderFallbackPrompt.requiresConfirmation(SessionOrigin.OPENAI_COMPATIBLE, BackendKind.OPENAI_COMPATIBLE_HTTP))
        assertFalse(ProviderFallbackPrompt.requiresConfirmation(SessionOrigin.CLAUDE, BackendKind.CLAUDE_CLI))
        assertFalse(ProviderFallbackPrompt.requiresConfirmation(SessionOrigin.CODEX, BackendKind.CODEX_APP_SERVER))
    }

    @Test
    fun `handoff INTO the HTTP provider requires confirmation`() {
        assertTrue(ProviderFallbackPrompt.requiresConfirmation(SessionOrigin.CLAUDE, BackendKind.OPENAI_COMPATIBLE_HTTP))
        assertTrue(ProviderFallbackPrompt.requiresConfirmation(SessionOrigin.CODEX, BackendKind.OPENAI_COMPATIBLE_HTTP))
    }

    @Test
    fun `handoff OUT of the HTTP provider requires confirmation`() {
        assertTrue(ProviderFallbackPrompt.requiresConfirmation(SessionOrigin.OPENAI_COMPATIBLE, BackendKind.CLAUDE_CLI))
        assertTrue(ProviderFallbackPrompt.requiresConfirmation(SessionOrigin.OPENAI_COMPATIBLE, BackendKind.CODEX_APP_SERVER))
    }

    @Test
    fun `pre-existing Claude and Codex switches are left unchanged`() {
        // Neither side is the HTTP provider → not gated here (existing behavior preserved).
        assertFalse(ProviderFallbackPrompt.requiresConfirmation(SessionOrigin.CLAUDE, BackendKind.CODEX_APP_SERVER))
        assertFalse(ProviderFallbackPrompt.requiresConfirmation(SessionOrigin.CODEX, BackendKind.CLAUDE_CLI))
    }
}
