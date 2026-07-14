package com.adobe.clawdea.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class PrettyModelNameTest {
    @Test
    fun `formats gpt-5-codex`() {
        assertEquals("GPT-5 Codex", ModelComboManager.prettyModelName("gpt-5-codex"))
    }

    @Test
    fun `formats gpt-5-mini`() {
        assertEquals("GPT-5 mini", ModelComboManager.prettyModelName("gpt-5-mini"))
    }

    @Test
    fun `leaves claude formatting intact`() {
        // Claude ids continue to render via the existing rules; a claude id must
        // not be prefixed with "GPT". Assert the label does not start with "GPT".
        assertEquals(false, ModelComboManager.prettyModelName("claude-sonnet-5").startsWith("GPT"))
    }
}
