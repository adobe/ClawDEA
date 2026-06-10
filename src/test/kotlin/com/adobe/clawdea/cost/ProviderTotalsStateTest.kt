package com.adobe.clawdea.cost

import com.adobe.clawdea.settings.ClawDEASettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderTotalsStateTest {
    @Test fun `provider totals map defaults empty and round-trips`() {
        val s = ClawDEASettings.State()
        assertEquals(0, s.providerTotals.size)
        s.providerTotals["bedrock"] = "2026-05-15|123.40|512.88|2026-06"
        assertEquals("2026-05-15|123.40|512.88|2026-06", s.providerTotals["bedrock"])
    }
}
