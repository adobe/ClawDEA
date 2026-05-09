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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingPromptControllerTest {

    @Test
    fun `queue marks non-blank composer text as pending`() {
        val controller = PendingPromptController()

        assertTrue(controller.queue("follow up"))

        assertTrue(controller.isQueued)
        assertEquals("Queued next prompt - will send after this turn", controller.statusText("follow up"))
    }

    @Test
    fun `queue clears pending state for blank text`() {
        val controller = PendingPromptController()
        controller.queue("follow up")

        assertFalse(controller.queue("   "))

        assertFalse(controller.isQueued)
        assertEquals("Claude is thinking...", controller.statusText("   "))
    }

    @Test
    fun `consume returns latest composer text and clears state`() {
        val controller = PendingPromptController()
        controller.queue("first version")

        val consumed = controller.consume("edited version")

        assertEquals("edited version", consumed)
        assertFalse(controller.isQueued)
    }

    @Test
    fun `pressing enter again keeps one queued prompt and latest composer text wins`() {
        val controller = PendingPromptController()
        controller.queue("first")
        controller.queue("second")

        val consumed = controller.consume("second edited")

        assertEquals("second edited", consumed)
        assertFalse(controller.isQueued)
    }

    @Test
    fun `consume clears state and returns null when composer was emptied`() {
        val controller = PendingPromptController()
        controller.queue("follow up")

        val consumed = controller.consume("   ")

        assertNull(consumed)
        assertFalse(controller.isQueued)
    }

    @Test
    fun `clear removes queued state`() {
        val controller = PendingPromptController()
        controller.queue("follow up")

        controller.clear()

        assertFalse(controller.isQueued)
        assertNull(controller.consume("follow up"))
    }
}
