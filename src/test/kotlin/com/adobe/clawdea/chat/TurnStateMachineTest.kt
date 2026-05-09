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

class TurnStateMachineTest {

    @Test
    fun `initial state is Idle`() {
        val sm = TurnStateMachine()
        assertEquals(TurnState.Idle, sm.state)
    }

    @Test
    fun `Escape while Streaming transitions to Paused and returns Pause action`() {
        val sm = TurnStateMachine()
        sm.handle(TurnEvent.UserSend)
        assertEquals(TurnState.Streaming, sm.state)

        val action = sm.handle(TurnEvent.Escape)

        assertEquals(TurnState.Paused, sm.state)
        assertEquals(TurnAction.Pause, action)
    }

    @Test
    fun `Enter with text while Paused returns ResumeWithInput and goes Streaming`() {
        val sm = streamingThenPaused()
        val action = sm.handle(TurnEvent.EnterInPaused(isBlank = false))
        assertEquals(TurnState.Streaming, sm.state)
        assertEquals(TurnAction.ResumeWithInput, action)
    }

    @Test
    fun `Enter blank while Paused returns ResumeWithContinue and goes Streaming`() {
        val sm = streamingThenPaused()
        val action = sm.handle(TurnEvent.EnterInPaused(isBlank = true))
        assertEquals(TurnState.Streaming, sm.state)
        assertEquals(TurnAction.ResumeWithContinue, action)
    }

    @Test
    fun `Escape while Paused after grace period returns FullyAbort and goes Idle`() {
        var now = 1000L
        val sm = streamingThenPaused(clock = { now })
        now += TurnStateMachine.PAUSE_GRACE_MS + 1
        val action = sm.handle(TurnEvent.Escape)
        assertEquals(TurnState.Idle, sm.state)
        assertEquals(TurnAction.FullyAbort, action)
    }

    @Test
    fun `Escape while Paused within grace period is ignored`() {
        var now = 1000L
        val sm = streamingThenPaused(clock = { now })
        now += TurnStateMachine.PAUSE_GRACE_MS - 100
        val action = sm.handle(TurnEvent.Escape)
        assertEquals(TurnState.Paused, sm.state)
        assertEquals(TurnAction.None, action)
    }

    @Test
    fun `StreamResult while Paused returns ClearPausedUi and goes Idle`() {
        val sm = streamingThenPaused()
        val action = sm.handle(TurnEvent.StreamResult)
        assertEquals(TurnState.Idle, sm.state)
        assertEquals(TurnAction.ClearPausedUi, action)
    }

    @Test
    fun `StreamResult while Streaming goes Idle with no action`() {
        val sm = TurnStateMachine()
        sm.handle(TurnEvent.UserSend)
        val action = sm.handle(TurnEvent.StreamResult)
        assertEquals(TurnState.Idle, sm.state)
        assertEquals(TurnAction.None, action)
    }

    @Test
    fun `SessionReset from Paused goes Idle`() {
        val sm = streamingThenPaused()
        sm.handle(TurnEvent.SessionReset)
        assertEquals(TurnState.Idle, sm.state)
    }

    @Test
    fun `SessionReset from Streaming goes Idle`() {
        val sm = TurnStateMachine()
        sm.handle(TurnEvent.UserSend)
        sm.handle(TurnEvent.SessionReset)
        assertEquals(TurnState.Idle, sm.state)
    }

    @Test
    fun `SessionReset from Idle stays Idle`() {
        val sm = TurnStateMachine()
        sm.handle(TurnEvent.SessionReset)
        assertEquals(TurnState.Idle, sm.state)
    }

    @Test
    fun `Escape while Idle is a no-op`() {
        val sm = TurnStateMachine()
        val action = sm.handle(TurnEvent.Escape)
        assertEquals(TurnState.Idle, sm.state)
        assertEquals(TurnAction.None, action)
    }

    private fun streamingThenPaused(clock: () -> Long = { 1000L }): TurnStateMachine {
        val sm = TurnStateMachine(clock)
        sm.handle(TurnEvent.UserSend)
        sm.handle(TurnEvent.Escape)
        check(sm.state == TurnState.Paused)
        return sm
    }
}
