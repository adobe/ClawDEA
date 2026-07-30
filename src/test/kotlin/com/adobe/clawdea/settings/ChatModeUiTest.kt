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
package com.adobe.clawdea.settings

import com.adobe.clawdea.provider.BackendKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Auto/Plan/Ask control maps onto the Claude CLI's --permission-mode, whose accepted values are
 * acceptEdits, auto, bypassPermissions, manual, dontAsk, plan. There is no "ask" value, and Auto
 * deliberately emits nothing so the tool-approval mode's own mapping still applies.
 */
class ChatModeUiTest {

    @Test
    fun `labels are the three segments in order`() {
        assertEquals(listOf("Auto", "Plan", "Ask"), ChatModeUi.labels)
    }

    @Test
    fun `label and key round-trip`() {
        assertEquals("auto", ChatModeUi.keyForLabel("Auto"))
        assertEquals("plan", ChatModeUi.keyForLabel("Plan"))
        assertEquals("ask", ChatModeUi.keyForLabel("Ask"))
        assertEquals("Auto", ChatModeUi.labelForKey("auto"))
        assertEquals("Plan", ChatModeUi.labelForKey("plan"))
        assertEquals("Ask", ChatModeUi.labelForKey("ask"))
    }

    @Test
    fun `unknown label or key falls back to auto`() {
        assertEquals("auto", ChatModeUi.keyForLabel("Nonsense"))
        assertEquals("auto", ChatModeUi.keyForLabel(""))
        assertEquals("Auto", ChatModeUi.labelForKey("nonsense"))
        assertEquals("Auto", ChatModeUi.labelForKey(""))
    }

    @Test
    fun `only plan maps to a permission-mode value`() {
        assertNull(ChatModeUi.permissionModeValue("auto"))
        assertEquals("plan", ChatModeUi.permissionModeValue("plan"))
        assertNull(ChatModeUi.permissionModeValue("ask"))
        assertNull(ChatModeUi.permissionModeValue("garbage"))
    }

    @Test
    fun `case and whitespace in a persisted key are tolerated`() {
        assertEquals("plan", ChatModeUi.keyForLabel(" plan "))
        assertEquals("Plan", ChatModeUi.labelForKey(" PLAN "))
        assertEquals("plan", ChatModeUi.permissionModeValue(" Plan "))
    }

    @Test
    fun `only the Claude CLI backend supports chat modes`() {
        assertTrue(ChatModeUi.isSupported(BackendKind.CLAUDE_CLI))
        assertFalse(ChatModeUi.isSupported(BackendKind.CODEX_APP_SERVER))
        assertFalse(ChatModeUi.isSupported(BackendKind.OPENAI_COMPATIBLE_HTTP))
    }

    @Test
    fun `a restart is needed only when the key actually changes`() {
        assertFalse(ChatModeUi.requiresCliRestart("auto", "auto"))
        assertTrue(ChatModeUi.requiresCliRestart("auto", "plan"))
        assertTrue(ChatModeUi.requiresCliRestart("plan", "ask"))
    }
}
