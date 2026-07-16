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

    @Test
    fun `explicit queued prompt survives blank composer text`() {
        val controller = PendingPromptController()

        assertTrue(controller.queueExplicit("generated refresh prompt"))

        assertTrue(controller.isQueued)
        assertTrue(controller.hasExplicitPrompt)
        assertEquals("Queued next prompt - will send after this turn", controller.statusText(""))
        assertEquals("generated refresh prompt", controller.consume(""))
        assertFalse(controller.isQueued)
    }

    @Test
    fun `composer queue replaces explicit queued prompt`() {
        val controller = PendingPromptController()
        controller.queueExplicit("generated refresh prompt")

        controller.queue("typed prompt")

        assertFalse(controller.hasExplicitPrompt)
        assertEquals("edited typed prompt", controller.consume("edited typed prompt"))
    }

    @Test
    fun `backend rebuild captures queued composer text without leaving it sendable on old bridge`() {
        val controller = PendingPromptController()
        controller.queue("queued follow-up")

        val transfer = controller.captureForBackendRebuild("queued follow-up")

        assertEquals("queued follow-up", transfer.visibleComposerText())
        assertFalse(controller.isQueued)
        assertNull(controller.consume("queued follow-up"))
    }

    @Test
    fun `backend rebuild preserves distinct explicit queue and composer draft`() {
        val controller = PendingPromptController()
        controller.queueExplicit("generated refresh prompt")

        val transfer = controller.captureForBackendRebuild("my unsent draft")

        assertEquals("generated refresh prompt", transfer.queuedPrompt)
        assertEquals("my unsent draft", transfer.composerText)
        assertEquals(
            "generated refresh prompt\n\nmy unsent draft",
            transfer.visibleComposerText(),
        )
        assertNull(controller.consume("my unsent draft"))
    }

    @Test
    fun `backend rebuild preserves an unqueued composer draft`() {
        val controller = PendingPromptController()

        val transfer = controller.captureForBackendRebuild("unsent draft")

        assertNull(transfer.queuedPrompt)
        assertEquals("unsent draft", transfer.visibleComposerText())
    }
}
