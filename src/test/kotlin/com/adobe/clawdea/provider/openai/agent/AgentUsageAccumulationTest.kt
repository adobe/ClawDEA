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

/**
 * A turn spans many rounds (one per tool-call batch) and each round reports its own Usage.
 * The terminal Result must carry the turn's TOTAL, so [AgentUsage.plus] is the accumulator
 * [AgentLoopController] uses instead of assignment.
 */
class AgentUsageAccumulationTest {

    @Test
    fun `plus sums every field`() {
        val first = AgentUsage(inputTokens = 100, outputTokens = 20, cachedInputTokens = 80, reasoningTokens = 5)
        val second = AgentUsage(inputTokens = 130, outputTokens = 35, cachedInputTokens = 100, reasoningTokens = 7)

        val total = first + second

        assertEquals(230, total.inputTokens)
        assertEquals(55, total.outputTokens)
        assertEquals(180, total.cachedInputTokens)
        assertEquals(12, total.reasoningTokens)
    }

    @Test
    fun `plus over a zero usage is identity`() {
        val usage = AgentUsage(inputTokens = 7, outputTokens = 9, cachedInputTokens = 3, reasoningTokens = 1)

        assertEquals(usage, AgentUsage() + usage)
        assertEquals(usage, usage + AgentUsage())
    }

    @Test
    fun `accumulating three rounds matches the arithmetic sum`() {
        val rounds = listOf(
            AgentUsage(inputTokens = 1_000, outputTokens = 50, cachedInputTokens = 900, reasoningTokens = 10),
            AgentUsage(inputTokens = 1_400, outputTokens = 70, cachedInputTokens = 1_200, reasoningTokens = 12),
            AgentUsage(inputTokens = 1_900, outputTokens = 90, cachedInputTokens = 1_600, reasoningTokens = 14),
        )

        val total = rounds.fold(AgentUsage()) { acc, round -> acc + round }

        assertEquals(4_300, total.inputTokens)
        assertEquals(210, total.outputTokens)
        assertEquals(3_700, total.cachedInputTokens)
        assertEquals(36, total.reasoningTokens)
    }
}
