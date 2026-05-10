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

import com.intellij.icons.AllIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ToolApprovalModeUiTest {

    @Test
    fun `allow safe is marked with warning severity`() {
        val mode = ToolApprovalModeUi.modeFor("Allow safe")

        assertEquals(ToolApprovalModeUi.Severity.WARNING, mode.severity)
        assertSame(AllIcons.General.Warning, mode.icon)
    }

    @Test
    fun `allow all is marked with danger severity`() {
        val mode = ToolApprovalModeUi.modeFor("Allow all")

        assertEquals(ToolApprovalModeUi.Severity.DANGER, mode.severity)
        assertSame(AllIcons.General.Error, mode.icon)
    }

    @Test
    fun `ask unlisted has no warning icon`() {
        val mode = ToolApprovalModeUi.modeFor("Ask unlisted")

        assertEquals(ToolApprovalModeUi.Severity.NONE, mode.severity)
        assertNull(mode.icon)
    }

    @Test
    fun `confirm-all key uses ask unlisted label`() {
        assertEquals("Ask unlisted", ToolApprovalModeUi.labelForKey("confirm-all"))
    }

    @Test
    fun `tooltip explains that listed allow and deny rules are honored`() {
        assert(ToolApprovalModeUi.TOOLTIP_TEXT.contains("Ask unlisted"))
        assert(ToolApprovalModeUi.TOOLTIP_TEXT.contains("previously allowed or denied"))
    }

    @Test
    fun `tooltip uses one line per approval option`() {
        assert(ToolApprovalModeUi.TOOLTIP_TEXT.startsWith("<html>"))
        assert(ToolApprovalModeUi.TOOLTIP_TEXT.contains("<br>Ask unlisted"))
        assert(ToolApprovalModeUi.TOOLTIP_TEXT.contains("<br>Allow safe"))
        assert(ToolApprovalModeUi.TOOLTIP_TEXT.contains("<br>Allow all"))
    }

    @Test
    fun `mode keys are stable for persisted settings`() {
        assertEquals(0, ToolApprovalModeUi.indexForKey("confirm-all"))
        assertEquals(1, ToolApprovalModeUi.indexForKey("allow-safe"))
        assertEquals(2, ToolApprovalModeUi.indexForKey("allow-all"))
        assertEquals("confirm-all", ToolApprovalModeUi.keyForIndex(-1))
        assertEquals("allow-all", ToolApprovalModeUi.keyForIndex(2))
        assertEquals("allow-all", ToolApprovalModeUi.keyForIndex(99))
    }

    @Test
    fun `changing approval mode requires CLI restart`() {
        assertEquals(false, ToolApprovalModeUi.requiresCliRestart("confirm-all", "confirm-all"))
        assertEquals(true, ToolApprovalModeUi.requiresCliRestart("allow-safe", "confirm-all"))
        assertEquals(true, ToolApprovalModeUi.requiresCliRestart("confirm-all", "allow-all"))
    }
}
