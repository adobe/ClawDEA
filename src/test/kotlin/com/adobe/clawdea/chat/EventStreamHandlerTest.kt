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
