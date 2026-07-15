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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the provider→backend classification that drives ChatPanel's provider-switch handling:
 * when [CliBridge.isCodexProvider] disagrees with a running bridge's fixed backend, the tab's
 * ChatSession must be rebuilt (the backend can't change in place). A regression here would let a
 * codex⇄claude auth switch silently keep the wrong backend/label while the model dropdown flips.
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
    fun `a codex to claude switch is a backend change`() {
        // The exact predicate ChatPanel uses: newProvider's backend vs. the running backend.
        val runningIsCodex = CliBridge.isCodexProvider("openai-subscription")
        val newIsCodex = CliBridge.isCodexProvider("bedrock")
        assertTrue("switching openai-subscription→bedrock must flip the backend", newIsCodex != runningIsCodex)
    }

    @Test
    fun `a same-backend provider switch is not a backend change`() {
        val runningIsCodex = CliBridge.isCodexProvider("anthropic")
        val newIsCodex = CliBridge.isCodexProvider("bedrock")
        assertFalse("anthropic→bedrock stays on claude; no rebuild needed", newIsCodex != runningIsCodex)
    }
}
