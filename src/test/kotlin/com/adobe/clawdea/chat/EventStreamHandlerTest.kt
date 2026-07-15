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

import com.adobe.clawdea.cli.CliEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventStreamHandlerTest {

    @Test
    fun `turn-start watchdog tolerates large primer first-byte latency`() {
        // 60s is too aggressive when the primer system prompt is large; ensure the
        // constant stays at a conservative ≥ 90s.
        assertTrue(
            EventStreamHandler.TURN_START_STALL_TIMEOUT_MS >= 90_000,
            "TURN_START_STALL_TIMEOUT_MS must be at least 90s; was ${EventStreamHandler.TURN_START_STALL_TIMEOUT_MS}",
        )
    }

    @Test
    fun `turn progress excludes startup and metadata events`() {
        assertFalse(EventStreamHandler.isTurnProgressEvent(CliEvent.SystemInit("", "", emptyList())))
        assertFalse(EventStreamHandler.isTurnProgressEvent(CliEvent.Unknown("user", "{}")))
        assertFalse(EventStreamHandler.isTurnProgressEvent(CliEvent.Unknown("stream_event", "{}")))
        assertTrue(EventStreamHandler.isTurnProgressEvent(CliEvent.TextDelta("hi")))
        assertTrue(EventStreamHandler.isTurnProgressEvent(CliEvent.AssistantMessage("", emptyList())))
        assertTrue(EventStreamHandler.isTurnProgressEvent(CliEvent.ToolResult("toolu_1", "")))
        assertTrue(EventStreamHandler.isTurnProgressEvent(CliEvent.Result("", false, 0.0, "session")))
    }

    @Test
    fun `goal feedback counts as turn progress`() {
        assertTrue(EventStreamHandler.isTurnProgressEvent(CliEvent.GoalFeedback("cond", "reason")))
    }

    @Test
    fun `activity indicator is re-asserted on messages and tool results while streaming`() {
        // Coarse activity events re-assert the hint so it survives a long
        // sub-agent run or a mid-session resume that dropped it.
        assertTrue(EventStreamHandler.shouldPokeIndicator(
            CliEvent.AssistantMessage("", emptyList()), isStreaming = true, isPaused = false,
        ))
        assertTrue(EventStreamHandler.shouldPokeIndicator(
            CliEvent.ToolResult("toolu_1", ""), isStreaming = true, isPaused = false,
        ))
    }

    @Test
    fun `streaming state is restored when the CLI keeps streaming after a false teardown`() {
        // A false-positive stall recovery resets isStreaming and hides the
        // indicator, but if the (still-alive) CLI keeps emitting coarse events the
        // turn UI must come back — this is the "still running, no indicator" symptom.
        assertTrue(EventStreamHandler.shouldRestoreStreaming(
            CliEvent.AssistantMessage("", emptyList()),
            isStreaming = false, isPaused = false, bridgeRunning = true,
        ))
        assertTrue(EventStreamHandler.shouldRestoreStreaming(
            CliEvent.ToolResult("toolu_1", ""),
            isStreaming = false, isPaused = false, bridgeRunning = true,
        ))
    }

    @Test
    fun `streaming state is not restored on a live turn, a stopped bridge, per-token, or a result`() {
        // Already streaming: nothing to restore.
        assertFalse(EventStreamHandler.shouldRestoreStreaming(
            CliEvent.AssistantMessage("", emptyList()),
            isStreaming = true, isPaused = false, bridgeRunning = true,
        ))
        // Bridge is down — there is no live turn to restore.
        assertFalse(EventStreamHandler.shouldRestoreStreaming(
            CliEvent.AssistantMessage("", emptyList()),
            isStreaming = false, isPaused = false, bridgeRunning = false,
        ))
        // Paused is a deliberate user state — do not silently resume it.
        assertFalse(EventStreamHandler.shouldRestoreStreaming(
            CliEvent.AssistantMessage("", emptyList()),
            isStreaming = false, isPaused = true, bridgeRunning = true,
        ))
        // Per-token deltas are not coarse activity.
        assertFalse(EventStreamHandler.shouldRestoreStreaming(
            CliEvent.TextDelta("hi"),
            isStreaming = false, isPaused = false, bridgeRunning = true,
        ))
        // A Result is terminal — it must never re-open a turn.
        assertFalse(EventStreamHandler.shouldRestoreStreaming(
            CliEvent.Result("", false, 0.0, "session"),
            isStreaming = false, isPaused = false, bridgeRunning = true,
        ))
    }

    @Test
    fun `streaming state is not restored once the turn has genuinely ended`() {
        // A real Result ended the turn. A stray trailing coarse event (some Claude turns emit a
        // late duplicate message after the result) must NOT resurrect the turn UI — otherwise the
        // activity indicator comes back with its pause button and no Result follows to hide it.
        assertFalse(EventStreamHandler.shouldRestoreStreaming(
            CliEvent.AssistantMessage("", emptyList()),
            isStreaming = false, isPaused = false, bridgeRunning = true,
            turnGenuinelyEnded = true,
        ))
        assertFalse(EventStreamHandler.shouldRestoreStreaming(
            CliEvent.ToolResult("toolu_1", ""),
            isStreaming = false, isPaused = false, bridgeRunning = true,
            turnGenuinelyEnded = true,
        ))
        // The false-stall self-heal still fires while the turn has not genuinely ended.
        assertTrue(EventStreamHandler.shouldRestoreStreaming(
            CliEvent.AssistantMessage("", emptyList()),
            isStreaming = false, isPaused = false, bridgeRunning = true,
            turnGenuinelyEnded = false,
        ))
    }

    @Test
    fun `activity indicator is not poked per-token, when paused, or when idle`() {
        // Per-token deltas would spam a JCEF round-trip — excluded.
        assertFalse(EventStreamHandler.shouldPokeIndicator(
            CliEvent.TextDelta("hi"), isStreaming = true, isPaused = false,
        ))
        // Result ends the turn and hides the indicator — must not fight the hide.
        assertFalse(EventStreamHandler.shouldPokeIndicator(
            CliEvent.Result("", false, 0.0, "session"), isStreaming = true, isPaused = false,
        ))
        // Never resurrect the hint after the turn ended or while paused.
        assertFalse(EventStreamHandler.shouldPokeIndicator(
            CliEvent.AssistantMessage("", emptyList()), isStreaming = false, isPaused = false,
        ))
        assertFalse(EventStreamHandler.shouldPokeIndicator(
            CliEvent.AssistantMessage("", emptyList()), isStreaming = true, isPaused = true,
        ))
    }

    @Test
    fun `only null-parent text streams into the main bubble buffer`() {
        // Main agent text (no parent) is buffered into the main bubble.
        assertTrue(EventStreamHandler.isMainAgentStream(null))
        // A depth-1 sub-agent's text carries the dispatching Agent's id — it
        // must route to that card, not the main stream.
        assertFalse(EventStreamHandler.isMainAgentStream("toolu_agent_1"))
        // A deeper nested sub-agent we don't track a card for must also be kept
        // out of the main stream (previously leaked and broke output mid-phrase).
        assertFalse(EventStreamHandler.isMainAgentStream("toolu_nested_deep"))
    }

    @Test
    fun `tool result stall recovery waits for streaming running bridge and no newer progress`() {
        assertTrue(EventStreamHandler.shouldRecoverToolResultStall(
            isStreaming = true,
            bridgeRunning = true,
            userInputPending = false,
            currentProgressSequence = 10,
            observedProgressSequence = 10,
        ))
        assertFalse(EventStreamHandler.shouldRecoverToolResultStall(
            isStreaming = false,
            bridgeRunning = true,
            userInputPending = false,
            currentProgressSequence = 10,
            observedProgressSequence = 10,
        ))
        assertFalse(EventStreamHandler.shouldRecoverToolResultStall(
            isStreaming = true,
            bridgeRunning = true,
            userInputPending = false,
            currentProgressSequence = 11,
            observedProgressSequence = 10,
        ))
        assertFalse(EventStreamHandler.shouldRecoverToolResultStall(
            isStreaming = true,
            bridgeRunning = true,
            userInputPending = true,
            currentProgressSequence = 10,
            observedProgressSequence = 10,
        ))
    }

    @Test
    fun `turn start stall recovery waits for streaming running bridge and no first progress`() {
        assertTrue(EventStreamHandler.shouldRecoverTurnStartStall(
            isStreaming = true,
            bridgeRunning = true,
            userInputPending = false,
            currentProgressSequence = 10,
            observedProgressSequence = 10,
        ))
        assertFalse(EventStreamHandler.shouldRecoverTurnStartStall(
            isStreaming = true,
            bridgeRunning = false,
            userInputPending = false,
            currentProgressSequence = 10,
            observedProgressSequence = 10,
        ))
        assertFalse(EventStreamHandler.shouldRecoverTurnStartStall(
            isStreaming = true,
            bridgeRunning = true,
            userInputPending = false,
            currentProgressSequence = 11,
            observedProgressSequence = 10,
        ))
        assertFalse(EventStreamHandler.shouldRecoverTurnStartStall(
            isStreaming = true,
            bridgeRunning = true,
            userInputPending = true,
            currentProgressSequence = 10,
            observedProgressSequence = 10,
        ))
    }
}
