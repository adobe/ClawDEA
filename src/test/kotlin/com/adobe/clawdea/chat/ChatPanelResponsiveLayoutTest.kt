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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for the responsive-layout aspect decision (issue #140): the chat panel
 * merges its top and bottom control bands into one compact row when docked
 * horizontally (wide and short) and keeps the classic two-band layout when docked
 * vertically (tall).
 */
class ChatPanelResponsiveLayoutTest {

    @Test
    fun `tall vertical dock uses the full two-band layout`() {
        // A right/left dock is much taller than wide.
        assertFalse(ChatPanel.shouldUseCompactLayout(width = 380, height = 900, currentlyCompact = false))
    }

    @Test
    fun `wide short horizontal dock switches to the compact layout`() {
        // A bottom/top dock is much wider than tall — limited height to spend.
        assertTrue(ChatPanel.shouldUseCompactLayout(width = 1400, height = 220, currentlyCompact = false))
    }

    @Test
    fun `the dead-band near square keeps the current arrangement`() {
        // Ratio 1.05 is inside [1.0, 1.2): neither switch fires, so state sticks.
        assertFalse(ChatPanel.shouldUseCompactLayout(width = 1050, height = 1000, currentlyCompact = false))
        assertTrue(ChatPanel.shouldUseCompactLayout(width = 1050, height = 1000, currentlyCompact = true))
    }

    @Test
    fun `crossing the thresholds flips the arrangement regardless of prior state`() {
        // Clearly wide → compact even if we were vertical.
        assertTrue(ChatPanel.shouldUseCompactLayout(width = 1300, height = 1000, currentlyCompact = false))
        // Square-or-taller → vertical even if we were compact.
        assertFalse(ChatPanel.shouldUseCompactLayout(width = 1000, height = 1000, currentlyCompact = true))
    }

    @Test
    fun `unknown zero size keeps the current state`() {
        // Before the panel is realized its size is 0x0 — don't thrash the layout.
        assertFalse(ChatPanel.shouldUseCompactLayout(width = 0, height = 0, currentlyCompact = false))
        assertTrue(ChatPanel.shouldUseCompactLayout(width = 0, height = 0, currentlyCompact = true))
    }
}
