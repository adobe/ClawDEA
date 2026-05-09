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

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SubscriptionAuthProbeTest {

    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("clawdea-probe-").toFile()
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `returns Unknown when CLI path is blank`() {
        val probe = SubscriptionAuthProbe(cliPath = "")
        assertEquals(AuthStatus.Unknown, probe.probe())
    }

    @Test
    fun `returns SignedIn with tier and email for claude-ai subscription`() {
        val fakeCli = writeFakeCli(
            """echo '{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","email":"alice@example.com","subscriptionType":"enterprise"}'""",
            exitCode = 0,
        )
        val probe = SubscriptionAuthProbe(cliPath = fakeCli.absolutePath)
        val status = probe.probe() as AuthStatus.SignedIn
        assertEquals("enterprise", status.tier)
        assertEquals("alice@example.com", status.email)
    }

    @Test
    fun `returns NotSignedIn when authMethod is third_party (Bedrock)`() {
        val fakeCli = writeFakeCli(
            """echo '{"loggedIn":true,"authMethod":"third_party","apiProvider":"bedrock"}'""",
            exitCode = 0,
        )
        val probe = SubscriptionAuthProbe(cliPath = fakeCli.absolutePath)
        assertEquals(AuthStatus.NotSignedIn, probe.probe())
    }

    @Test
    fun `returns NotSignedIn when auth status reports loggedIn false`() {
        val fakeCli = writeFakeCli(
            """echo '{"loggedIn":false}'""",
            exitCode = 0,
        )
        val probe = SubscriptionAuthProbe(cliPath = fakeCli.absolutePath)
        assertEquals(AuthStatus.NotSignedIn, probe.probe())
    }

    @Test
    fun `returns NotSignedIn when auth status JSON omits loggedIn`() {
        val fakeCli = writeFakeCli("echo 'some totally unexpected output'", exitCode = 0)
        val probe = SubscriptionAuthProbe(cliPath = fakeCli.absolutePath)
        assertEquals(AuthStatus.NotSignedIn, probe.probe())
    }

    @Test
    fun `returns Invalid when CLI exits with auth error`() {
        val fakeCli = writeFakeCli(
            """echo 'credentials expired' >&2""",
            exitCode = 1,
        )
        val probe = SubscriptionAuthProbe(cliPath = fakeCli.absolutePath)
        val status = probe.probe()
        assertTrue("expected Invalid, got $status", status is AuthStatus.Invalid)
    }

    @Test
    fun `returns Unknown on CLI timeout`() {
        val fakeCli = writeFakeCli("sleep 10", exitCode = 0)
        val probe = SubscriptionAuthProbe(
            cliPath = fakeCli.absolutePath,
            timeoutMillis = 300,
        )
        assertEquals(AuthStatus.Unknown, probe.probe())
    }

    @Test
    fun `returns Unknown when CLI binary does not exist`() {
        val probe = SubscriptionAuthProbe(cliPath = File(tmpDir, "nonexistent").absolutePath)
        assertEquals(AuthStatus.Unknown, probe.probe())
    }

    private fun writeFakeCli(body: String, exitCode: Int): File {
        val script = File(tmpDir, "fake-claude.sh")
        script.writeText(
            """
            #!/bin/sh
            $body
            exit $exitCode
            """.trimIndent()
        )
        script.setExecutable(true)
        return script
    }
}
