/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.cost

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CodexRateLimitMapper]: maps the codex app-server `RateLimitSnapshot` payload onto
 * ClawDEA's [SubscriptionUsage] credit gauge.
 */
class CodexRateLimitMapperTest {

    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `individualLimit maps to a credits spend gauge`() {
        val u = CodexRateLimitMapper.map(
            obj("""{"individualLimit":{"used":"176","limit":"440","remainingPercent":60,"resetsAt":1234}}"""),
            nowEpochMs = 100,
        )!!
        assertTrue(u.available)
        val spend = u.spend!!
        assertEquals(176.0, spend.used, 0.0)
        assertEquals(440.0, spend.limit, 0.0)
        assertEquals(40, spend.pct) // 100 - remainingPercent
        assertEquals("credits", spend.currency)
        assertTrue(spend.isCredits)
    }

    @Test
    fun `pct derives from used over limit when remainingPercent absent`() {
        val u = CodexRateLimitMapper.map(obj("""{"individualLimit":{"used":"50","limit":"200"}}"""))!!
        assertEquals(25, u.spend!!.pct)
    }

    @Test
    fun `primary and secondary windows map with duration labels`() {
        val u = CodexRateLimitMapper.map(
            obj(
                """{"primary":{"usedPercent":30,"windowDurationMins":300,"resetsAt":10},
                   "secondary":{"usedPercent":80,"windowDurationMins":10080,"resetsAt":20}}""",
            ),
        )!!
        assertEquals(2, u.windows.size)
        assertEquals("5h", u.windows[0].label)
        assertEquals(30, u.windows[0].pct)
        assertEquals("7d", u.windows[1].label)
        assertEquals(80, u.windows[1].pct)
    }

    @Test
    fun `credits balance is a fallback gauge when no individualLimit`() {
        val u = CodexRateLimitMapper.map(obj("""{"credits":{"hasCredits":true,"unlimited":false,"balance":"500"}}"""))!!
        val spend = u.spend!!
        assertEquals(500.0, spend.limit, 0.0)
        assertTrue(spend.isCredits)
    }

    @Test
    fun `unlimited credits with no windows yields null`() {
        val u = CodexRateLimitMapper.map(obj("""{"credits":{"hasCredits":true,"unlimited":true}}"""))
        assertNull(u)
    }

    @Test
    fun `individualLimit takes precedence over credits balance`() {
        val u = CodexRateLimitMapper.map(
            obj("""{"individualLimit":{"used":"1","limit":"10"},"credits":{"hasCredits":true,"unlimited":false,"balance":"999"}}"""),
        )!!
        assertEquals(10.0, u.spend!!.limit, 0.0)
    }

    @Test
    fun `empty snapshot yields null`() {
        assertNull(CodexRateLimitMapper.map(obj("{}")))
        assertNull(CodexRateLimitMapper.map(null))
    }

    @Test
    fun `window label formats days hours minutes`() {
        assertEquals("5h", CodexRateLimitMapper.windowLabel(300))
        assertEquals("7d", CodexRateLimitMapper.windowLabel(10080))
        assertEquals("45m", CodexRateLimitMapper.windowLabel(45))
        assertEquals("rate limit", CodexRateLimitMapper.windowLabel(null))
    }
}
