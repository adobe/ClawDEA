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
    fun `formats bare gpt-5`() {
        assertEquals("GPT-5", ModelComboManager.prettyModelName("gpt-5"))
    }

    @Test
    fun `leaves claude formatting intact`() {
        assertEquals("Sonnet 5", ModelComboManager.prettyModelName("claude-sonnet-5"))
    }
}
