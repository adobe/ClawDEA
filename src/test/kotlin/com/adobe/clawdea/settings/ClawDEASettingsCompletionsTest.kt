package com.adobe.clawdea.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClawDEASettingsCompletionsTest {

    @Test
    fun `manual-only completions are disabled by default`() {
        // Issue #146: the hotkey/manual-only mode is opt-in; the default keeps
        // the automatic as-you-type behavior.
        val state = ClawDEASettings.State()
        assertFalse(state.completionsManualOnly)
        assertTrue(state.completionsEnabled)
        assertEquals(300, state.completionsDebounceMs)
    }

    @Test
    fun `manual-only flag is mutable and round-trips`() {
        val state = ClawDEASettings.State()
        state.completionsManualOnly = true
        assertTrue(state.completionsManualOnly)
        state.completionsManualOnly = false
        assertFalse(state.completionsManualOnly)
    }
}
