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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the prompt-stall escalation logic.
 *
 * Background: Claude Code can leave a session in a poisoned state where every
 * `--resume <id>` produces "No response requested." (or just silence) instead
 * of an actual answer. The first stall is usually transient (cold model, slow
 * first byte, brief network blip), so we resume. The second consecutive stall
 * means resume isn't going to work and we must restart fresh.
 */
class ChatPanelStallEscalationTest {

    @Test
    fun `first prompt-start stall keeps the session via resume`() {
        assertFalse(ChatPanel.shouldEscalateToFreshRestart(consecutivePromptStalls = 1))
    }

    @Test
    fun `second consecutive stall escalates to a fresh restart`() {
        assertTrue(ChatPanel.shouldEscalateToFreshRestart(consecutivePromptStalls = 2))
    }

    @Test
    fun `further consecutive stalls keep restarting fresh`() {
        assertTrue(ChatPanel.shouldEscalateToFreshRestart(consecutivePromptStalls = 3))
        assertTrue(ChatPanel.shouldEscalateToFreshRestart(consecutivePromptStalls = 5))
    }

    @Test
    fun `stalled-prompt message reflects whether we are escalating`() {
        val resumeMsg = ChatPanel.stalledPromptMessage(escalateToFresh = false)
        val freshMsg = ChatPanel.stalledPromptMessage(escalateToFresh = true)

        assertTrue(resumeMsg.contains("Restarting"))
        assertFalse(resumeMsg.contains("fresh"))

        assertTrue(freshMsg.contains("fresh"))
        assertTrue(freshMsg.contains("stuck"))
    }
}
