package com.adobe.clawdea.settings

import org.junit.Assert.*
import org.junit.Test

class ClawDEASettingsCostStateTest {

    @Test
    fun `cost fields default to empty-zero`() {
        val state = ClawDEASettings.State()
        assertEquals(0.0, state.dailyCostUsd, 0.0)
        assertEquals("", state.dailyCostDate)
        assertEquals(0.0, state.dailyBudgetUsd, 0.0)
    }

    @Test
    fun `cost fields are mutable and round-trip`() {
        val state = ClawDEASettings.State()
        state.dailyCostUsd = 1.25
        state.dailyCostDate = "2026-06-10"
        state.dailyBudgetUsd = 5.0
        assertEquals(1.25, state.dailyCostUsd, 0.0)
        assertEquals("2026-06-10", state.dailyCostDate)
        assertEquals(5.0, state.dailyBudgetUsd, 0.0)
    }
}
