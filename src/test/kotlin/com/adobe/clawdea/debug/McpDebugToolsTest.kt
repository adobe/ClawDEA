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
// src/test/kotlin/com/adobe/clawdea/debug/McpDebugToolsTest.kt
package com.adobe.clawdea.debug

import org.junit.Assert.*
import org.junit.Test

class McpDebugToolsTest {

    @Test
    fun `parseLine returns error for missing arg`() {
        val result = McpDebugTools.parseLine(emptyMap())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("line"))
    }

    @Test
    fun `parseLine returns error for non-numeric`() {
        val result = McpDebugTools.parseLine(mapOf("line" to "abc"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `parseLine returns int for valid value`() {
        val result = McpDebugTools.parseLine(mapOf("line" to "42"))
        assertEquals(42, result.getOrThrow())
    }

    @Test
    fun `parsePort returns error for missing arg`() {
        val result = McpDebugTools.parsePort(emptyMap())
        assertTrue(result.isFailure)
    }

    @Test
    fun `parsePort returns error for out of range`() {
        val result = McpDebugTools.parsePort(mapOf("port" to "99999"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `parsePort returns int for valid value`() {
        val result = McpDebugTools.parsePort(mapOf("port" to "5005"))
        assertEquals(5005, result.getOrThrow())
    }

    @Test
    fun `parseFrameIndex defaults to 0 when omitted`() {
        assertEquals(0, McpDebugTools.parseFrameIndex(emptyMap()))
    }

    @Test
    fun `parseFrameIndex parses provided value`() {
        assertEquals(3, McpDebugTools.parseFrameIndex(mapOf("frame_index" to "3")))
    }

    @Test
    fun `formatSuspendInfo formats position`() {
        val info = SuspendInfo("Main.kt", 42, "main")
        val text = McpDebugTools.formatSuspendInfo(info)
        assertTrue(text.contains("Main.kt:42"))
        assertTrue(text.contains("main"))
    }

    @Test
    fun `formatSuspendInfo formats session ended`() {
        val info = SuspendInfo(null, -1, null, exitCode = 0)
        val text = McpDebugTools.formatSuspendInfo(info)
        assertTrue(text.contains("exited"))
    }

    @Test
    fun `formatSuspendInfo formats timeout`() {
        val text = McpDebugTools.formatSuspendInfo(null)
        assertTrue(text.contains("running"))
    }
}
