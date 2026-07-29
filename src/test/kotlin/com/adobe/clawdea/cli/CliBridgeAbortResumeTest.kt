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
package com.adobe.clawdea.cli

import com.adobe.clawdea.cli.backend.AgentBackend
import com.adobe.clawdea.cli.backend.SteeringMode
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore
import com.adobe.clawdea.settings.ClawDEASettings
import com.adobe.clawdea.skills.SkillInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue

/**
 * Guards that an aborted persistent-backend session can stream again on the next send.
 *
 * The regression: after an abort, Codex and OpenAI-compatible backends stayed alive, but
 * CliBridge's generation-scoped mute (`expectedExitGeneration = activeGeneration`) never cleared
 * because the backend never restarted. Every subsequent event of the session was dropped at
 * `canReaderEmit`, so one ESC permanently silenced the tab.
 *
 * The fix: turn-scoped `turnMuted` for backends whose `abortTerminatesProcess` is false.
 */
class CliBridgeAbortResumeTest {

    @Test
    fun `abort then send streams the second turn on a persistent backend`() = runBlocking {
        val fakeBackend = FakePersistentBackend(
            turnScripts = listOf(
                listOf(CliEvent.TextDelta("first turn")),
                listOf(CliEvent.TextDelta("second turn")),
            )
        )
        val bridge = CliBridge(
            workingDirectory = "",
            selection = null,
            settings = ClawDEASettings(),
            credentialStore = ProfileCredentialStore(),
            effectiveProviderIdProvider = { "anthropic" },
            backendFactory = { _ -> fakeBackend },
        )

        // Collect events in the background (SharedFlow replay=0 requires early subscription)
        val collectedEvents = mutableListOf<CliEvent>()
        val collectorJob = launch {
            bridge.events.collect { collectedEvents.add(it) }
        }

        try {
            bridge.start(null, emptyList())

            // Wait for SystemInit to be consumed
            delay(200)

            // First turn: send message, events are produced by sendMessage
            bridge.sendMessage("turn 1")

            // Poll collected events
            var firstEvent: CliEvent.TextDelta? = null
            repeat(30) {
                firstEvent = collectedEvents.filterIsInstance<CliEvent.TextDelta>().firstOrNull()
                if (firstEvent != null) return@repeat
                delay(100)
            }
            assertNotNull("First turn event should arrive", firstEvent)
            assertEquals("first turn", firstEvent!!.text)

            // Abort while the backend is still "running"
            bridge.abort()

            // Second turn: send message, events are produced by sendMessage (no race)
            bridge.sendMessage("turn 2")

            // Poll collected events for second turn
            var secondEvent: CliEvent.TextDelta? = null
            repeat(30) {
                secondEvent = collectedEvents.filterIsInstance<CliEvent.TextDelta>()
                    .firstOrNull { it.text == "second turn" }
                if (secondEvent != null) return@repeat
                delay(100)
            }

            // The regression would time out here (all events muted).
            assertNotNull("Second turn event should arrive after abort", secondEvent)
            assertEquals("second turn", secondEvent!!.text)
        } finally {
            collectorJob.cancel()
            bridge.dispose()
        }
    }
}

/**
 * Fake AgentBackend for a persistent backend (Codex/OpenAI-compatible): `abortTerminatesProcess`
 * is false, `isAlive` stays true across an abort, and `readEvent()` blocks on a queue. Events are
 * driven by `sendMessage` (mirroring the real backend) to avoid the race where the reader consumes
 * and drops a muted event before `sendMessage` clears the mute.
 */
class FakePersistentBackend(
    private val turnScripts: List<List<CliEvent>> = emptyList(),
) : AgentBackend {
    private val eventQueue = LinkedBlockingQueue<Any>()
    @Volatile
    private var started = false
    private var turnIndex = 0

    override val isAlive: Boolean get() = started
    override val backendKind: BackendKind = BackendKind.CODEX_APP_SERVER
    override val agentLabel: String = "Fake"
    override val steeringMode: SteeringMode = SteeringMode.NONE
    override val abortTerminatesProcess: Boolean = false

    override fun start(resumeSessionId: String?, skills: List<SkillInfo>) {
        started = true
        // Emit SystemInit immediately on start
        eventQueue.put(CliEvent.SystemInit(sessionId = "fake-session", model = "fake-model", tools = emptyList()))
    }

    override fun readEvent(): CliEvent? = try {
        val item = eventQueue.take()
        if (item === EOF_SENTINEL) null else item as CliEvent
    } catch (e: InterruptedException) {
        null
    }

    override fun sendMessage(text: String) {
        // Drive events from sendMessage, as the real backend does. This prevents the race where
        // an event enqueued before sendMessage is consumed and dropped by a muted reader before
        // sendMessage clears the mute.
        if (turnIndex < turnScripts.size) {
            turnScripts[turnIndex].forEach { eventQueue.put(it) }
            turnIndex++
        }
    }

    override fun abort() {
        // Persistent backend: stays alive, just cancels the turn
    }

    override fun steer(text: String): Boolean = false

    override fun stop() {
        started = false
        // Enqueue EOF sentinel so readEvent() returns null and the reader loop exits, mirroring
        // OpenAiCompatibleAgentBackend.stop() which enqueues EOF_SENTINEL. Without this, the
        // reader coroutine stays parked in take() forever and leaks into Dispatchers.IO.
        eventQueue.put(EOF_SENTINEL)
    }

    override fun recentErrors(): List<String> = emptyList()

    companion object {
        private val EOF_SENTINEL = Any()
    }
}
