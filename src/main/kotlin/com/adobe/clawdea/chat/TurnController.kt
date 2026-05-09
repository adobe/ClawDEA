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

class TurnController(
    private val onPause: () -> Unit,
    private val onResume: (text: String) -> Unit,
    private val onAbort: () -> Unit,
    private val stateMachine: TurnStateMachine = TurnStateMachine(),
) {

    val isPaused: Boolean get() = stateMachine.state == TurnState.Paused
    var isStreaming: Boolean = false
        private set

    fun setStreaming(streaming: Boolean) {
        isStreaming = streaming
        if (streaming && stateMachine.state == TurnState.Idle) {
            stateMachine.handle(TurnEvent.UserSend)
        }
    }

    fun onUserSend() {
        stateMachine.handle(TurnEvent.UserSend)
        isStreaming = true
    }

    fun handleEscape() {
        val action = stateMachine.handle(TurnEvent.Escape)
        executeAction(action)
    }

    fun enterInPaused(rawText: String, isBlank: Boolean) {
        val action = stateMachine.handle(TurnEvent.EnterInPaused(isBlank = isBlank))
        when (action) {
            TurnAction.ResumeWithContinue -> onResume("continue")
            TurnAction.ResumeWithInput -> onResume(rawText.trim())
            else -> {}
        }
    }

    fun onStreamResult() {
        val action = stateMachine.handle(TurnEvent.StreamResult)
        isStreaming = false
        executeAction(action)
    }

    fun resetTurnState() {
        stateMachine.handle(TurnEvent.SessionReset)
        isStreaming = false
    }

    private fun executeAction(action: TurnAction) {
        when (action) {
            TurnAction.Pause -> {
                isStreaming = false
                onPause()
            }
            TurnAction.FullyAbort -> onAbort()
            TurnAction.ClearPausedUi -> {}
            TurnAction.None, TurnAction.ResumeWithContinue, TurnAction.ResumeWithInput -> {}
        }
    }
}
