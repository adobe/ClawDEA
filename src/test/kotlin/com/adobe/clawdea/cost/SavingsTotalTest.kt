package com.adobe.clawdea.cost

import org.junit.Assert.assertEquals
import org.junit.Test

class SavingsTotalTest {

    @Test
    fun `round trips through pack and parse`() {
        val t = SavingsTotal(
            sinceDate = "2026-03-14",
            mtd = SavingsBand(1.0, 2.0, 3.0),
            allTime = SavingsBand(4.0, 5.0, 6.0),
            mtdMonth = "2026-06",
        )
        val back = SavingsTotal.parse(SavingsTotal.format(t))
        assertEquals(t, back)
    }

    @Test
    fun `add accumulates all time and current month`() {
        val start = SavingsTotal.empty()
        val after = start.add(SavingsBand(0.1, 0.2, 0.3), today = "2026-06-16", month = "2026-06")
        assertEquals(0.2, after.allTime.expected, 1e-9)
        assertEquals(0.2, after.mtd.expected, 1e-9)
        assertEquals("2026-06-16", after.sinceDate)
        assertEquals("2026-06", after.mtdMonth)
    }

    @Test
    fun `add rolls month-to-date over on month change but all-time keeps growing`() {
        val june = SavingsTotal.empty().add(SavingsBand(1.0, 1.0, 1.0), "2026-06-30", "2026-06")
        val july = june.add(SavingsBand(2.0, 2.0, 2.0), "2026-07-01", "2026-07")
        assertEquals(2.0, july.mtd.expected, 1e-9)
        assertEquals(3.0, july.allTime.expected, 1e-9)
        assertEquals("2026-07", july.mtdMonth)
    }

    @Test
    fun `parse of blank yields empty`() {
        val e = SavingsTotal.parse("")
        assertEquals(SavingsBand.ZERO, e.allTime)
        assertEquals(SavingsBand.ZERO, e.mtd)
    }
}
