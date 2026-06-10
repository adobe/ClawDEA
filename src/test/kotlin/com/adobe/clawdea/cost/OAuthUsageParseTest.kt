package com.adobe.clawdea.cost

import org.junit.Assert.*
import org.junit.Test

class OAuthUsageParseTest {
    private fun fixture(name: String) =
        OAuthUsageParseTest::class.java.getResourceAsStream("/oauth-usage/$name")!!.bufferedReader().readText()

    @Test fun `parses enterprise dollar spend`() {
        val u = OAuthUsageClient.parse(fixture("enterprise.json"))
        assertTrue(u.available)
        assertNotNull(u.spend)
        assertEquals(542.98, u.spend!!.usedUsd, 1e-6)
        assertEquals(600.0, u.spend.limitUsd, 1e-6)
        assertTrue(u.windows.isEmpty())
    }

    @Test fun `parses pro windows`() {
        val u = OAuthUsageClient.parse(fixture("pro.json"))
        assertTrue(u.available)
        assertNull(u.spend)
        assertEquals(3, u.windows.size)
        assertEquals("5-hour", u.windows[0].label)
        assertEquals(21, u.windows[0].pct)
    }

    @Test fun `malformed yields unavailable`() {
        assertFalse(OAuthUsageClient.parse("not json").available)
        assertFalse(OAuthUsageClient.parse("{}").available)
    }
}
