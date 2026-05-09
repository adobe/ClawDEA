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

/** UI-only turn state for the chat panel. Pause is not observed by the CLI. */
enum class TurnState { Idle, Streaming, Paused }

/** Event inputs that can change the turn state. */
sealed class TurnEvent {
    /** User pressed the ESC keybinding or triggered the Pause/Abort action. */
    object Escape : TurnEvent()

    /** User pressed Enter in the input area while paused. `isBlank` is true if the
     *  field is empty or still shows the placeholder. */
    data class EnterInPaused(val isBlank: Boolean) : TurnEvent()

    /** The CLI emitted a Result event, closing the current turn. */
    object StreamResult : TurnEvent()

    /** A user action started a new turn (send, skill invocation). */
    object UserSend : TurnEvent()

    /** Session-level reset (new chat, restart, bridge.stop). */
    object SessionReset : TurnEvent()
}

/** Side effects the panel should execute in response to a transition. */
sealed class TurnAction {
    /** SIGINT the CLI, show banner, flush partial output, focus input. */
    object Pause : TurnAction()

    /** Send `continue` to the CLI on empty-Enter resume, with a rendered user bubble. */
    object ResumeWithContinue : TurnAction()

    /** Send the current input text to the CLI, with a rendered user bubble. */
    object ResumeWithInput : TurnAction()

    /** Banner off, render "Response aborted by user". */
    object FullyAbort : TurnAction()

    /** Banner off (no error render); used on race where Result arrives after SIGINT. */
    object ClearPausedUi : TurnAction()

    /** No-op. */
    object None : TurnAction()
}

class TurnStateMachine(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var state: TurnState = TurnState.Idle
        private set

    private var pausedAt: Long = 0

    fun handle(event: TurnEvent): TurnAction {
        val (next, action) = transition(state, event)
        if (next == TurnState.Paused && state != TurnState.Paused) {
            pausedAt = clock()
        }
        state = next
        return action
    }

    private fun transition(current: TurnState, event: TurnEvent): Pair<TurnState, TurnAction> =
        when (current) {
            TurnState.Idle -> when (event) {
                TurnEvent.UserSend -> TurnState.Streaming to TurnAction.None
                TurnEvent.SessionReset -> TurnState.Idle to TurnAction.None
                else -> current to TurnAction.None
            }
            TurnState.Streaming -> when (event) {
                TurnEvent.Escape -> TurnState.Paused to TurnAction.Pause
                TurnEvent.StreamResult -> TurnState.Idle to TurnAction.None
                TurnEvent.SessionReset -> TurnState.Idle to TurnAction.None
                else -> current to TurnAction.None
            }
            TurnState.Paused -> when (event) {
                TurnEvent.Escape ->
                    if (clock() - pausedAt < PAUSE_GRACE_MS) current to TurnAction.None
                    else TurnState.Idle to TurnAction.FullyAbort
                is TurnEvent.EnterInPaused ->
                    TurnState.Streaming to
                        if (event.isBlank) TurnAction.ResumeWithContinue
                        else TurnAction.ResumeWithInput
                TurnEvent.StreamResult -> TurnState.Idle to TurnAction.ClearPausedUi
                TurnEvent.SessionReset -> TurnState.Idle to TurnAction.ClearPausedUi
                else -> current to TurnAction.None
            }
        }

    companion object {
        const val PAUSE_GRACE_MS = 600L
    }
}
