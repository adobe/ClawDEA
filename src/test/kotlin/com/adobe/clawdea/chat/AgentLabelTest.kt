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

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentLabelTest {

    @Test
    fun `codex-backed providers map to Codex`() {
        assertEquals("Codex", AgentLabel.forProvider("openai"))
        assertEquals("Codex", AgentLabel.forProvider("openai-subscription"))
    }

    @Test
    fun `claude-backed providers map to Claude`() {
        assertEquals("Claude", AgentLabel.forProvider("anthropic"))
        assertEquals("Claude", AgentLabel.forProvider("bedrock"))
        assertEquals("Claude", AgentLabel.forProvider("subscription"))
        assertEquals("Claude", AgentLabel.forProvider("vertex"))
    }

    @Test
    fun `unknown provider defaults to Claude`() {
        assertEquals("Claude", AgentLabel.forProvider("something-else"))
    }

    @Test
    fun `generic HTTP provider uses its registry label`() {
        assertEquals("OpenAI-compatible", AgentLabel.forProvider("openai-compatible"))
    }
}
