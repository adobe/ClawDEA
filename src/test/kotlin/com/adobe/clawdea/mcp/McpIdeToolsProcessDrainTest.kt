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
package com.adobe.clawdea.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the drain-before-waitFor invariant for the tier-4 build-tool compile. A Gradle/Maven
 * compile writes far more than the OS pipe buffer, so waiting on the process before reading its
 * output deadlocks: the child blocks writing, we block waiting, and the tool reports a bogus
 * timeout while killing a compile that was working.
 */
class McpIdeToolsProcessDrainTest {

    @Test
    fun `captures output larger than the pipe buffer without timing out`() {
        // 400_000 bytes — comfortably past the typical 64 KB pipe buffer.
        val builder = ProcessBuilder("sh", "-c", "yes 0123456789 | head -c 400000; exit 0")
            .redirectErrorStream(true)

        val run = McpIdeTools.runCapturingOutput(builder, timeoutMillis = 30_000)

        assertFalse("must not time out draining 400 KB", run.timedOut)
        assertEquals(0, run.exitCode)
        assertEquals(400_000, run.output.length)
    }

    @Test
    fun `reports the exit code of a failing process`() {
        val builder = ProcessBuilder("sh", "-c", "echo boom; exit 3").redirectErrorStream(true)

        val run = McpIdeTools.runCapturingOutput(builder, timeoutMillis = 30_000)

        assertFalse(run.timedOut)
        assertEquals(3, run.exitCode)
        assertTrue(run.output.contains("boom"))
    }

    @Test
    fun `reports a timeout for a process that outlives the budget`() {
        val builder = ProcessBuilder("sh", "-c", "sleep 30").redirectErrorStream(true)

        val started = System.currentTimeMillis()
        val run = McpIdeTools.runCapturingOutput(builder, timeoutMillis = 500)
        val elapsed = System.currentTimeMillis() - started

        assertTrue("must report a timeout", run.timedOut)
        assertTrue("must return near the budget, took ${elapsed}ms", elapsed < 10_000)
    }
}
