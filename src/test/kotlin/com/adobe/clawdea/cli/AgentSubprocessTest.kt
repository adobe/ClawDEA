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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Exercises the real drain/timeout/kill behavior against `/bin/sh` (POSIX hosts only). */
class AgentSubprocessTest {

    @Before
    fun requiresPosixShell() {
        assumeTrue("POSIX /bin/sh required", File("/bin/sh").canExecute())
    }

    @Test
    fun `captures stdout and the exit code`() {
        val r = AgentSubprocess.run(listOf("/bin/sh", "-c", "printf hello; exit 3"), timeoutMillis = 5_000)
        assertFalse(r.timedOut)
        assertEquals(3, r.exitCode)
        assertEquals("hello", r.stdout)
    }

    @Test
    fun `captures stderr separately from stdout`() {
        val r = AgentSubprocess.run(
            listOf("/bin/sh", "-c", "printf out; printf err 1>&2"),
            timeoutMillis = 5_000,
        )
        assertEquals("out", r.stdout)
        assertEquals("err", r.stderr)
    }

    @Test
    fun `large output does not deadlock (drains past the OS pipe buffer)`() {
        // ~420 KB, far beyond the ~64 KB pipe buffer that a read-after-waitFor runner deadlocks on.
        val r = AgentSubprocess.run(
            listOf("/bin/sh", "-c", "i=0; while [ \$i -lt 20000 ]; do echo aaaaaaaaaaaaaaaaaaaa; i=\$((i+1)); done"),
            timeoutMillis = 30_000,
        )
        assertFalse("must not deadlock/time out on large output", r.timedOut)
        assertEquals(0, r.exitCode)
        assertTrue("expected large stdout, got ${r.stdout.length}", r.stdout.length > 100_000)
    }

    @Test
    fun `a slow process is force-killed at the timeout`() {
        val start = System.currentTimeMillis()
        val r = AgentSubprocess.run(listOf("/bin/sh", "-c", "sleep 10"), timeoutMillis = 300)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("timedOut flag must be set", r.timedOut)
        assertEquals(-1, r.exitCode)
        assertTrue("must return promptly after the timeout, took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun `configureEnvironment controls the child environment`() {
        val r = AgentSubprocess.run(
            listOf("/bin/sh", "-c", "printf %s \"\$FOO\""),
            timeoutMillis = 5_000,
            configureEnvironment = { it["FOO"] = "bar" },
        )
        assertEquals("bar", r.stdout)
    }

    @Test
    fun `redirectErrorStream merges stderr into stdout`() {
        val r = AgentSubprocess.run(
            listOf("/bin/sh", "-c", "printf out; printf err 1>&2"),
            timeoutMillis = 5_000,
            redirectErrorStream = true,
        )
        assertTrue(r.stdout.contains("out"))
        assertTrue(r.stdout.contains("err"))
        assertEquals("", r.stderr)
    }
}
