package com.adobe.clawdea.cost

import org.junit.Assert.*
import org.junit.Test

/**
 * The cache is the heart of the app-level usage refactor: one shared poll publishes into it,
 * and every open project subscribes a sink. These tests lock down the two behaviors that the
 * old per-project poller got wrong — divergence between projects and the up-to-40m "stuck
 * unavailable" window for a project that opened (or recovered) mid-cycle.
 */
class OAuthUsageCacheTest {

    private val available = SubscriptionUsage(true, spend = SubscriptionUsage.Spend(1.0, 10.0, 50, "USD"))

    @Test fun `a new subscriber is replayed the latest cached value immediately`() {
        val cache = OAuthUsageCache()
        cache.publish(available) // poll already succeeded once before this project opened

        var seen: SubscriptionUsage? = null
        cache.subscribe { seen = it }

        // Without replay, a project opening after the poll would sit at UNAVAILABLE until the
        // next successful fetch — up to 40 minutes away. Replay seeds it instantly.
        assertEquals(available, seen)
    }

    @Test fun `a fresh subscriber with no prior publish sees UNAVAILABLE`() {
        val cache = OAuthUsageCache()
        var seen: SubscriptionUsage? = null
        cache.subscribe { seen = it }
        assertEquals(SubscriptionUsage.UNAVAILABLE, seen)
    }

    @Test fun `publish fans out to every subscriber so projects never diverge`() {
        val cache = OAuthUsageCache()
        var a: SubscriptionUsage? = null
        var b: SubscriptionUsage? = null
        cache.subscribe { a = it }
        cache.subscribe { b = it }

        cache.publish(available)

        assertEquals(available, a)
        assertEquals(available, b)
    }

    @Test fun `unsubscribe stops further delivery`() {
        val cache = OAuthUsageCache()
        var count = 0
        val unsubscribe = cache.subscribe { count++ } // replay = 1
        cache.publish(available) // = 2
        unsubscribe()
        cache.publish(SubscriptionUsage.UNAVAILABLE) // must NOT reach the removed sink

        assertEquals(2, count)
    }
}
