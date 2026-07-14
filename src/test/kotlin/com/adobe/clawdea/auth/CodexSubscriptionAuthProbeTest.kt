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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSubscriptionAuthProbeTest {

    @Test
    fun `blank cli path yields Unknown`() {
        assertEquals(AuthStatus.Unknown, CodexSubscriptionAuthProbe(cliPath = "").probe())
    }

    @Test
    fun `nonexistent cli path yields Unknown`() {
        // The bogus path fails to start the subprocess, which the probe maps to Unknown.
        val probe = CodexSubscriptionAuthProbe(
            cliPath = "/nonexistent/definitely-not-codex-binary-xyz",
            timeoutMillis = 2000,
        )
        assertEquals(AuthStatus.Unknown, probe.probe())
    }

    @Test
    fun `parses logged in using ChatGPT as SignedIn`() {
        assertEquals(
            AuthStatus.SignedIn(tier = null, email = null),
            CodexSubscriptionAuthProbe.parseLoginStatus("Logged in using ChatGPT", "", 0),
        )
    }

    @Test
    fun `parses not logged in as NotSignedIn`() {
        // "Not logged in" contains "logged in" — the negative match must win.
        assertEquals(
            AuthStatus.NotSignedIn,
            CodexSubscriptionAuthProbe.parseLoginStatus("Not logged in", "", 0),
        )
    }

    @Test
    fun `non-zero exit with auth error hint yields Invalid`() {
        val status = CodexSubscriptionAuthProbe.parseLoginStatus("", "invalid token", 1)
        assertTrue(status is AuthStatus.Invalid)
        assertEquals("invalid token", (status as AuthStatus.Invalid).reason)
    }

    @Test
    fun `unrecognized output yields Unknown`() {
        assertEquals(
            AuthStatus.Unknown,
            CodexSubscriptionAuthProbe.parseLoginStatus("some unexpected banner", "", 0),
        )
    }
}
