package com.adobe.clawdea.cost

import org.junit.Assert.*
import org.junit.Test

/**
 * Backoff curve for the single app-level usage poll. With one shared poll a long ceiling is no
 * longer needed to spare the endpoint, and a 40m blind window after a few transient failures hurt
 * recovery — so the curve escalates once (5m → 10m) and then holds at a 10m ceiling.
 */
class OAuthUsageBackoffTest {

    private val min = 60_000L

    @Test fun `first failure waits the 5m base`() {
        assertEquals(5 * min, OAuthUsageService.backoffMs(0))
    }

    @Test fun `escalates to the 10m ceiling on the second failure`() {
        assertEquals(10 * min, OAuthUsageService.backoffMs(1))
    }

    @Test fun `holds at the 10m ceiling and never exceeds it`() {
        assertEquals(10 * min, OAuthUsageService.backoffMs(2))
        assertEquals(10 * min, OAuthUsageService.backoffMs(3))
        // Defensive: even an un-coerced large failure count stays capped.
        assertEquals(10 * min, OAuthUsageService.backoffMs(20))
    }
}
