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
package com.adobe.clawdea.auth

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CodexSubscriptionAuthTest {

    @Test
    fun `getStatus returns NotSignedIn when probe returns NotSignedIn`() {
        val auth = CodexSubscriptionAuth(probeFactory = { StaticProbe(AuthStatus.NotSignedIn) })
        assertEquals(AuthStatus.NotSignedIn, auth.getStatusBlocking())
    }

    @Test
    fun `getStatus returns cached result within TTL`() {
        val counter = AtomicInteger(0)
        val probe = CountingProbe(AuthStatus.SignedIn(null, null), counter)
        val auth = CodexSubscriptionAuth(probeFactory = { probe }, cacheTtlMillis = 10_000)
        assertEquals(AuthStatus.SignedIn(null, null), auth.getStatusBlocking())
        assertEquals(AuthStatus.SignedIn(null, null), auth.getStatusBlocking())
        assertEquals("probe invoked only once within TTL", 1, counter.get())
    }

    @Test
    fun `invalidateCache forces a re-probe`() {
        val counter = AtomicInteger(0)
        val probe = CountingProbe(AuthStatus.SignedIn(null, null), counter)
        val auth = CodexSubscriptionAuth(probeFactory = { probe }, cacheTtlMillis = 10_000)
        auth.getStatusBlocking()
        auth.invalidateCache()
        auth.getStatusBlocking()
        assertEquals(2, counter.get())
    }

    @Test
    fun `status transitions flow through the listener`() {
        val current = AtomicReference<AuthStatus>(AuthStatus.NotSignedIn)
        val auth = CodexSubscriptionAuth(probeFactory = { DynamicProbe(current) })
        val received = mutableListOf<AuthStatus>()
        auth.addListener(object : CodexSubscriptionAuthEventListener {
            override fun onStatusChanged(status: AuthStatus) { received.add(status) }
        })
        auth.getStatusBlocking()
        current.set(AuthStatus.SignedIn(null, null))
        auth.invalidateCache()
        auth.getStatusBlocking()
        assertEquals(
            listOf(AuthStatus.NotSignedIn, AuthStatus.SignedIn(null, null)),
            received,
        )
    }

    @Test
    fun `signIn invokes runner with codex login and invalidates cache`() {
        val counter = AtomicInteger(0)
        val probe = CountingProbe(AuthStatus.NotSignedIn, counter)
        val captured = AtomicReference<List<String>>(emptyList())
        val runner = FakeRunner { cmd ->
            captured.set(cmd)
            ProcessOutcome(exit = 0, stdout = "", stderr = "")
        }
        val auth = CodexSubscriptionAuth(
            probeFactory = { probe },
            processRunner = runner,
            cliPath = "/opt/codex",
        )
        auth.getStatusBlocking() // seed cache
        var final: AuthStatus? = null
        auth.signInBlocking { final = it }
        assertEquals(listOf("/opt/codex", "login"), captured.get())
        assertEquals(2, counter.get())
        assertNotNull(final)
    }

    @Test
    fun `signOut invokes codex logout and updates status`() {
        val initial = AtomicReference<AuthStatus>(AuthStatus.SignedIn(null, null))
        val captured = AtomicReference<List<String>>(emptyList())
        val probe = DynamicProbe(initial)
        val runner = FakeRunner { cmd ->
            captured.set(cmd)
            initial.set(AuthStatus.NotSignedIn)
            ProcessOutcome(exit = 0, stdout = "", stderr = "")
        }
        val auth = CodexSubscriptionAuth(
            probeFactory = { probe },
            processRunner = runner,
            cliPath = "/opt/codex",
        )
        auth.getStatusBlocking()
        var final: AuthStatus? = null
        auth.signOutBlocking { final = it }
        assertEquals(listOf("/opt/codex", "logout"), captured.get())
        assertEquals(AuthStatus.NotSignedIn, final)
    }

    @Test
    fun `signIn surfaces failure reason when subprocess exits non-zero`() {
        val probe = StaticProbe(AuthStatus.NotSignedIn)
        val runner = FakeRunner { _ ->
            ProcessOutcome(exit = 1, stdout = "", stderr = "device-auth failed")
        }
        val auth = CodexSubscriptionAuth(
            probeFactory = { probe },
            processRunner = runner,
            cliPath = "/opt/codex",
        )
        var final: AuthStatus? = null
        auth.signInBlocking { final = it }
        assertEquals(AuthStatus.NotSignedIn, final)
        assertEquals("device-auth failed", auth.lastSignInError())
    }

    private class FakeRunner(val handler: (List<String>) -> ProcessOutcome) : ProcessRunner {
        override fun run(command: List<String>, timeoutMillis: Long): ProcessOutcome =
            handler(command)
    }

    private class StaticProbe(val status: AuthStatus) : SubscriptionAuthProbeFacade {
        override fun probe() = status
    }

    private class CountingProbe(
        val status: AuthStatus,
        val counter: AtomicInteger,
    ) : SubscriptionAuthProbeFacade {
        override fun probe(): AuthStatus {
            counter.incrementAndGet()
            return status
        }
    }

    private class DynamicProbe(val ref: AtomicReference<AuthStatus>) : SubscriptionAuthProbeFacade {
        override fun probe() = ref.get()
    }
}
