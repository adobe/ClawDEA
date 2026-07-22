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
package com.adobe.clawdea.provider.openai.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallLoopGuardTest {

    private fun guard() = ToolCallLoopGuard(nudgeThreshold = 3, stopThreshold = 6)

    @Test
    fun `executes until nudge threshold, then nudges, then stops`() {
        val g = guard()
        val sig = g.signature("read_wiki_page", """{"name":"missing"}""")

        // 3 failing executes (counts 0,1,2 all EXECUTE)
        repeat(3) {
            assertEquals(ToolCallLoopGuard.Decision.EXECUTE, g.decide(sig))
            g.recordResult(sig, isError = true)
        }
        // Now count == 3 → NUDGE
        assertEquals(ToolCallLoopGuard.Decision.NUDGE, g.decide(sig))
        // Nudges keep escalating the count
        repeat(3) {
            assertEquals(ToolCallLoopGuard.Decision.NUDGE, g.decide(sig))
            g.recordNudge(sig)
        }
        // count == 6 → STOP
        assertEquals(ToolCallLoopGuard.Decision.STOP, g.decide(sig))
    }

    @Test
    fun `a success resets the streak`() {
        val g = guard()
        val sig = g.signature("some_tool", "{}")
        repeat(3) { g.recordResult(sig, isError = true) }
        assertEquals(ToolCallLoopGuard.Decision.NUDGE, g.decide(sig))

        g.recordResult(sig, isError = false) // succeeded this time
        assertEquals(0, g.repeatCount(sig))
        assertEquals(ToolCallLoopGuard.Decision.EXECUTE, g.decide(sig))
    }

    @Test
    fun `distinct arguments are distinct signatures and never trip the guard`() {
        val g = guard()
        repeat(10) { i ->
            val sig = g.signature("search_text", """{"query":"q$i"}""")
            assertEquals(ToolCallLoopGuard.Decision.EXECUTE, g.decide(sig))
            g.recordResult(sig, isError = true)
        }
    }

    @Test
    fun `repeated successful calls never trip the guard`() {
        val g = guard()
        val sig = g.signature("read", """{"f":"x"}""")
        repeat(10) {
            assertEquals(ToolCallLoopGuard.Decision.EXECUTE, g.decide(sig))
            g.recordResult(sig, isError = false)
        }
    }
}
