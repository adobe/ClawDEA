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
package com.adobe.clawdea.chat

import org.junit.Assert.*
import org.junit.Test

class ContextBudgetIndicatorTest {

    @Test
    fun `estimateTokens returns roughly 1 token per 4 characters`() {
        val text = "a".repeat(400)
        val tokens = ContextBudgetCalculator.estimateTokens(text)
        assertEquals(100, tokens)
    }

    @Test
    fun `estimateTokens returns 0 for empty string`() {
        assertEquals(0, ContextBudgetCalculator.estimateTokens(""))
    }

    @Test
    fun `estimateTokens rounds up`() {
        val tokens = ContextBudgetCalculator.estimateTokens("abc")
        assertEquals(1, tokens)
    }

    @Test
    fun `calculatePercentage returns 0 when budget is 0`() {
        assertEquals(0, ContextBudgetCalculator.calculatePercentage(100, 0))
    }

    @Test
    fun `calculatePercentage returns correct percentage`() {
        assertEquals(50, ContextBudgetCalculator.calculatePercentage(500, 1000))
    }

    @Test
    fun `calculatePercentage caps at 100`() {
        assertEquals(100, ContextBudgetCalculator.calculatePercentage(2000, 1000))
    }

    @Test
    fun `formatBudgetText shows tokens and percentage`() {
        val result = ContextBudgetCalculator.formatBudgetText(512, 2048)
        assertEquals("512 / 2,048 tokens (25%)", result)
    }

    @Test
    fun `formatBudgetText handles zero budget`() {
        val result = ContextBudgetCalculator.formatBudgetText(100, 0)
        assertEquals("100 / 0 tokens (0%)", result)
    }
}
