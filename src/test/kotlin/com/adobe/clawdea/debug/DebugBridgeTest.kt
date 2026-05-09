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
// src/test/kotlin/com/adobe/clawdea/debug/DebugBridgeTest.kt
package com.adobe.clawdea.debug

import org.junit.Assert.*
import org.junit.Test

class DebugBridgeTest {

    @Test
    fun `SessionStatus toString formats correctly`() {
        val status = SessionStatus(
            active = true,
            suspended = true,
            file = "Main.kt",
            line = 42,
            method = "main",
            sessionType = "java_app",
        )
        val text = status.toText()
        assertTrue(text.contains("active"))
        assertTrue(text.contains("suspended"))
        assertTrue(text.contains("Main.kt"))
        assertTrue(text.contains("42"))
        assertTrue(text.contains("main"))
    }

    @Test
    fun `SessionStatus formats idle state`() {
        val status = SessionStatus.NONE
        val text = status.toText()
        assertTrue(text.contains("No debug session"))
    }

    @Test
    fun `FrameInfo formats with all fields`() {
        val frame = FrameInfo(0, "com/app/Main.kt", 10, "main", "Main")
        val text = frame.toText()
        assertTrue(text.contains("#0"))
        assertTrue(text.contains("Main.kt"))
        assertTrue(text.contains("main"))
    }

    @Test
    fun `VariableInfo formats primitive value`() {
        val v = VariableInfo("count", "int", "42", expandable = false)
        val text = v.toText()
        assertTrue(text.contains("count"))
        assertTrue(text.contains("int"))
        assertTrue(text.contains("42"))
    }

    @Test
    fun `VariableInfo formats expandable object`() {
        val v = VariableInfo("list", "ArrayList", "{size=3}", expandable = true)
        val text = v.toText()
        assertTrue(text.contains("[expandable]"))
    }

    @Test
    fun `EvalResult formats value`() {
        val r = EvalResult("String", "hello", expandable = false)
        val text = r.toText()
        assertTrue(text.contains("String"))
        assertTrue(text.contains("hello"))
    }

    @Test
    fun `EvalResult formats error`() {
        val r = EvalResult.error("Cannot evaluate: variable not found")
        val text = r.toText()
        assertTrue(text.contains("Error"))
        assertTrue(text.contains("variable not found"))
    }

    @Test
    fun `BreakpointInfo formats with condition`() {
        val bp = BreakpointInfo("Foo.kt", 10, enabled = true, claudeOwned = true, condition = "x > 5", logExpression = null)
        val text = bp.toText()
        assertTrue(text.contains("Foo.kt:10"))
        assertTrue(text.contains("claude"))
        assertTrue(text.contains("x > 5"))
    }

    @Test
    fun `BreakpointInfo formats user-owned disabled`() {
        val bp = BreakpointInfo("Bar.kt", 20, enabled = false, claudeOwned = false, condition = null, logExpression = null)
        val text = bp.toText()
        assertTrue(text.contains("disabled"))
        assertTrue(text.contains("user"))
    }
}
