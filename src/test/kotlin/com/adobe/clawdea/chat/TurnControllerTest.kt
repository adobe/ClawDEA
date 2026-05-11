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
import kotlin.test.assertEquals

class TurnControllerTest {

    @Test
    fun `pause calls onPause and sets streaming false`() {
        var pauseCalled = false
        val controller = TurnController(
            onPause = { pauseCalled = true },
            onResume = {},
            onAbort = {},
        )
        controller.setStreaming(true)
        controller.handleEscape()
        assertTrue(pauseCalled)
        assertFalse(controller.isStreaming)
        assertTrue(controller.isPaused)
    }

    @Test
    fun `enterInPaused with blank resumes with continue`() {
        var resumeText: String? = null
        val controller = TurnController(
            onPause = {},
            onResume = { text -> resumeText = text },
            onAbort = {},
        )
        controller.setStreaming(true)
        controller.handleEscape()
        controller.enterInPaused("", isBlank = true)
        assertEquals("continue", resumeText)
    }

    @Test
    fun `enterInPaused with text resumes with that text`() {
        var resumeText: String? = null
        val controller = TurnController(
            onPause = {},
            onResume = { text -> resumeText = text },
            onAbort = {},
        )
        controller.setStreaming(true)
        controller.handleEscape()
        controller.enterInPaused("fix the bug", isBlank = false)
        assertEquals("fix the bug", resumeText)
    }

    @Test
    fun `double escape aborts after grace period`() {
        var now = 1000L
        var abortCalled = false
        val controller = TurnController(
            onPause = {},
            onResume = {},
            onAbort = { abortCalled = true },
            stateMachine = TurnStateMachine(clock = { now }),
        )
        controller.setStreaming(true)
        controller.handleEscape()
        now += TurnStateMachine.PAUSE_GRACE_MS + 1
        controller.handleEscape()
        assertTrue(abortCalled)
    }

    @Test
    fun `double escape within grace period does not abort`() {
        var now = 1000L
        var abortCalled = false
        val controller = TurnController(
            onPause = {},
            onResume = {},
            onAbort = { abortCalled = true },
            stateMachine = TurnStateMachine(clock = { now }),
        )
        controller.setStreaming(true)
        controller.handleEscape()
        now += 100
        controller.handleEscape()
        assertFalse(abortCalled)
        assertTrue(controller.isPaused)
    }

    @Test
    fun `onUserSend sets streaming`() {
        val controller = TurnController(onPause = {}, onResume = {}, onAbort = {})
        controller.onUserSend()
        assertTrue(controller.isStreaming)
    }

    @Test
    fun `resetTurnState clears all state`() {
        val controller = TurnController(onPause = {}, onResume = {}, onAbort = {})
        controller.setStreaming(true)
        controller.resetTurnState()
        assertFalse(controller.isStreaming)
        assertFalse(controller.isPaused)
    }

    @Test
    fun `streamResult while paused calls onClearPausedUi`() {
        var cleared = false
        val controller = TurnController(
            onPause = {},
            onResume = {},
            onAbort = {},
            onClearPausedUi = { cleared = true },
        )
        controller.setStreaming(true)
        controller.handleEscape()
        assertTrue(controller.isPaused)
        controller.onStreamResult()
        assertTrue(cleared)
        assertFalse(controller.isStreaming)
        assertFalse(controller.isPaused)
    }
}
