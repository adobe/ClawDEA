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

enum class TurnControlButton {
    NONE,
    PAUSE,
    STOP,
}

data class TurnControlsState(
    val selectorsEnabled: Boolean,
    val thinkingButton: TurnControlButton,
) {
    companion object {
        fun from(isStreaming: Boolean, isPaused: Boolean): TurnControlsState {
            val button = when {
                isPaused -> TurnControlButton.STOP
                isStreaming -> TurnControlButton.PAUSE
                else -> TurnControlButton.NONE
            }
            return TurnControlsState(
                selectorsEnabled = !isStreaming,
                thinkingButton = button,
            )
        }
    }
}
