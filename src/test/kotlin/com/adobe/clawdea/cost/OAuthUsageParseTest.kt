package com.adobe.clawdea.cost

import org.junit.Assert.*
import org.junit.Test

class OAuthUsageParseTest {
    private fun fixture(name: String) =
        OAuthUsageParseTest::class.java.getResourceAsStream("/oauth-usage/$name")!!.bufferedReader().readText()

    @Test fun `parses spend gauge from extra_usage`() {
        val u = OAuthUsageClient.parse(fixture("spend.json"))
        assertTrue(u.available)
        assertNotNull(u.spend)
        assertEquals(54652.0, u.spend!!.used, 1e-6)
        assertEquals(60000.0, u.spend.limit, 1e-6)
        assertEquals(91, u.spend.pct) // 91.0867 rounds to 91
        assertEquals("USD", u.spend.currency)
        assertTrue(u.windows.isEmpty())
    }

    @Test fun `parses known windows and drops codename keys`() {
        val u = OAuthUsageClient.parse(fixture("windows.json"))
        assertTrue(u.available)
        assertNull(u.spend)
        // five_hour, seven_day, seven_day_sonnet are known; tangelo/iguana_necktie are dropped.
        assertEquals(listOf("5-hour", "7-day", "7-day Sonnet"), u.windows.map { it.label })
        assertEquals(21, u.windows[0].pct) // 21.4 rounds to 21
        assertEquals(40, u.windows[1].pct)
    }

    @Test fun `live sample parses to spend with no surfaced windows`() {
        // The real captured response: all named windows null, only extra_usage populated.
        val u = OAuthUsageClient.parse(fixture("live-sample.json"))
        assertTrue(u.available)
        assertNotNull(u.spend)
        assertEquals(91, u.spend!!.pct)
        assertTrue(u.windows.isEmpty())
    }

    @Test fun `all-null and malformed yield unavailable`() {
        assertFalse(OAuthUsageClient.parse(fixture("all-null.json")).available)
        assertFalse(OAuthUsageClient.parse("not json").available)
        assertFalse(OAuthUsageClient.parse("{}").available)
    }
}
