package com.adobe.clawdea.cost

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderTotalTest {
    @Test fun `parse then format round-trips`() {
        val t = ProviderTotal.parse("2026-05-15|123.40|512.88|2026-06")
        assertEquals("2026-05-15", t.sinceDate)
        assertEquals(123.40, t.monthToDate, 1e-9)
        assertEquals(512.88, t.allTime, 1e-9)
        assertEquals("2026-06", t.mtdMonth)
        assertEquals("2026-05-15|123.4|512.88|2026-06", ProviderTotal.format(t))
    }
    @Test fun `blank parses to zeroed`() {
        val t = ProviderTotal.parse("")
        assertEquals(0.0, t.monthToDate, 0.0)
        assertEquals(0.0, t.allTime, 0.0)
    }
    @Test fun `add accrues both totals within same month`() {
        val t = ProviderTotal("2026-06-01", 10.0, 50.0, "2026-06")
        val r = t.add(5.0, today = "2026-06-20", month = "2026-06")
        assertEquals(15.0, r.monthToDate, 1e-9)
        assertEquals(55.0, r.allTime, 1e-9)
    }
    @Test fun `add resets MTD on month change but not all-time`() {
        val t = ProviderTotal("2026-06-01", 10.0, 50.0, "2026-06")
        val r = t.add(5.0, today = "2026-07-02", month = "2026-07")
        assertEquals(5.0, r.monthToDate, 1e-9)
        assertEquals(55.0, r.allTime, 1e-9)
        assertEquals("2026-07", r.mtdMonth)
    }
}
