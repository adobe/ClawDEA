package com.adobe.clawdea.cost

import org.junit.Assert.assertEquals
import org.junit.Test

class LeverBandStoreTest {

    @Test
    fun `format and parse round trip`() {
        val band = SavingsBand(0.1, 0.25, 0.4)
        assertEquals(band, LeverBandStore.parse(LeverBandStore.format(band)))
    }

    @Test
    fun `accrue sums into stored map`() {
        val stored = mutableMapOf<String, String>()
        LeverBandStore.accrue(stored, LeverId.INDEX_TOOLS, SavingsBand(0.01, 0.02, 0.03))
        LeverBandStore.accrue(stored, LeverId.INDEX_TOOLS, SavingsBand(0.01, 0.02, 0.03))
        val band = LeverBandStore.readAll(stored)[LeverId.INDEX_TOOLS]!!
        assertEquals(0.04, band.expected, 1e-9)
    }
}
