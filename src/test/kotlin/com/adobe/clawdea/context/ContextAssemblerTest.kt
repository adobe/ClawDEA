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
// src/test/kotlin/com/adobe/clawdea/context/ContextAssemblerTest.kt
package com.adobe.clawdea.context

import org.junit.Assert.*
import org.junit.Test

class ContextAssemblerTest {

    private val assembler = ContextAssembler()

    @Test
    fun `assemble returns items sorted by score descending`() {
        val items = listOf(
            ContextItem("low", "low content", 0.2, "test"),
            ContextItem("high", "high content", 0.9, "test"),
            ContextItem("mid", "mid content", 0.5, "test"),
        )
        val result = assembler.assemble(items, tokenBudget = 10000)
        assertEquals("high", result[0].label)
        assertEquals("mid", result[1].label)
        assertEquals("low", result[2].label)
    }

    @Test
    fun `assemble trims to token budget`() {
        val items = listOf(
            ContextItem("a", "x".repeat(4000), 0.9, "test"),  // ~1000 tokens
            ContextItem("b", "y".repeat(4000), 0.8, "test"),  // ~1000 tokens
            ContextItem("c", "z".repeat(4000), 0.7, "test"),  // ~1000 tokens
        )
        // Budget of 2200 should keep items a and b but drop c
        val result = assembler.assemble(items, tokenBudget = 2200)
        assertEquals(2, result.size)
        assertEquals("a", result[0].label)
        assertEquals("b", result[1].label)
    }

    @Test
    fun `assemble returns empty list for empty input`() {
        val result = assembler.assemble(emptyList(), tokenBudget = 1000)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `assemble skips oversized items and keeps smaller ones`() {
        val items = listOf(
            ContextItem("huge", "x".repeat(8000), 0.9, "test"),
            ContextItem("tiny", "y".repeat(40), 0.8, "test"),
        )
        val result = assembler.assemble(items, tokenBudget = 20)
        assertEquals(1, result.size)
        assertEquals("tiny", result[0].label)
    }

    @Test
    fun `assemble with zero budget returns empty`() {
        val items = listOf(ContextItem("a", "content", 1.0, "test"))
        assertTrue(assembler.assemble(items, tokenBudget = 0).isEmpty())
    }

    @Test
    fun `format returns empty string for empty list`() {
        assertEquals("", assembler.format(emptyList()))
    }

    @Test
    fun `format produces structured text with labels`() {
        val items = listOf(
            ContextItem("Current method", "fun doGet() { }", 0.9, "psi"),
            ContextItem("Import", "import java.util.List", 0.5, "file"),
        )
        val formatted = assembler.format(items)
        assertTrue(formatted.contains("--- Current method ---"))
        assertTrue(formatted.contains("fun doGet() { }"))
        assertTrue(formatted.contains("--- Import ---"))
        assertTrue(formatted.contains("import java.util.List"))
    }
}
