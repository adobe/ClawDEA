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
package com.adobe.clawdea.debug

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.util.concurrent.Executors

class SuspendGateTest {

    @Test
    fun `awaitSuspend returns info when onSuspended called before timeout`() {
        val gate = SuspendGate()
        gate.arm()
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            Thread.sleep(50)
            gate.onSuspended(SuspendInfo("Foo.kt", 42, "doStuff"))
        }
        val result = gate.awaitSuspend(Duration.ofSeconds(5))
        assertNotNull(result)
        assertEquals("Foo.kt", result!!.file)
        assertEquals(42, result.line)
        assertEquals("doStuff", result.method)
        executor.shutdown()
    }

    @Test
    fun `awaitSuspend returns null on timeout`() {
        val gate = SuspendGate()
        gate.arm()
        val result = gate.awaitSuspend(Duration.ofMillis(50))
        assertNull(result)
    }

    @Test
    fun `awaitSuspend returns null when not armed`() {
        val gate = SuspendGate()
        val result = gate.awaitSuspend(Duration.ofMillis(50))
        assertNull(result)
    }

    @Test
    fun `onSessionEnded completes with null file`() {
        val gate = SuspendGate()
        gate.arm()
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            Thread.sleep(50)
            gate.onSessionEnded(0)
        }
        val result = gate.awaitSuspend(Duration.ofSeconds(5))
        assertNotNull(result)
        assertNull(result!!.file)
        assertEquals(0, result.exitCode)
        executor.shutdown()
    }

    @Test
    fun `arm replaces previous future`() {
        val gate = SuspendGate()
        gate.arm()
        gate.arm() // re-arm
        gate.onSuspended(SuspendInfo("Bar.kt", 10, "init"))
        val result = gate.awaitSuspend(Duration.ofSeconds(1))
        assertNotNull(result)
        assertEquals("Bar.kt", result!!.file)
    }

    @Test
    fun `disarm cancels pending wait`() {
        val gate = SuspendGate()
        gate.arm()
        gate.disarm()
        val result = gate.awaitSuspend(Duration.ofMillis(50))
        assertNull(result)
    }
}
