package com.adobe.clawdea.cost

import org.junit.Assert.assertEquals
import org.junit.Test

class SavingsEstimatorTest {

    @Test
    fun `band plus band adds componentwise`() {
        val a = SavingsBand(1.0, 2.0, 3.0)
        val b = SavingsBand(0.5, 0.5, 0.5)
        val sum = a + b
        assertEquals(1.5, sum.low, 1e-9)
        assertEquals(2.5, sum.expected, 1e-9)
        assertEquals(3.5, sum.high, 1e-9)
    }

    @Test
    fun `band negate flips and swaps low high`() {
        val a = SavingsBand(1.0, 2.0, 5.0)
        val n = -a
        assertEquals(-5.0, n.low, 1e-9)
        assertEquals(-2.0, n.expected, 1e-9)
        assertEquals(-1.0, n.high, 1e-9)
    }

    @Test
    fun `exact builds a zero-width band`() {
        val e = SavingsBand.exact(-0.05)
        assertEquals(-0.05, e.low, 1e-9)
        assertEquals(-0.05, e.expected, 1e-9)
        assertEquals(-0.05, e.high, 1e-9)
    }
}
