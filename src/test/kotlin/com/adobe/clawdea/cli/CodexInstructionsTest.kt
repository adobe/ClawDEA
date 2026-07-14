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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexInstructionsTest {

    @Test
    fun `compose drops blank blocks and joins with a blank line`() {
        assertEquals("a\n\nb", CodexInstructions.compose("a", "", "b"))
        assertEquals("only", CodexInstructions.compose("", "only", ""))
        assertEquals("", CodexInstructions.compose("", "  ", ""))
    }

    @Test
    fun `prepend returns the raw prompt when there is no preamble`() {
        assertEquals("do it", CodexInstructions.prepend("", "do it"))
        assertEquals("do it", CodexInstructions.prepend("   ", "do it"))
    }

    @Test
    fun `prepend wraps the user request under a marker when a preamble exists`() {
        val out = CodexInstructions.prepend("RULES", "fix the bug")
        assertTrue(out.startsWith("RULES"))
        assertTrue(out.contains("User request:"))
        assertTrue(out.trimEnd().endsWith("fix the bug"))
    }

    @Test
    fun `codex tooling prompt resource ships and routes edits through propose tools`() {
        val prompt = com.adobe.clawdea.knowledge.prompts.PromptResource.load("codex-tooling-prompt")
        assertTrue(prompt.contains("propose_edit"))
        assertTrue(prompt.contains("propose_write"))
        assertTrue(prompt.contains("apply_patch"))
        assertTrue(prompt.contains("find_symbol"))
    }
}
