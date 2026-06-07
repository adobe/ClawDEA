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
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.util.concurrent.TimeUnit

class CliProcessRegistryTest {

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    @Test fun `killAll force-kills a registered live process and returns the count`() {
        assumeFalse("uses unix sleep", isWindows)
        val proc = ProcessBuilder("sleep", "60").start()
        CliProcessRegistry.register(proc)
        assertTrue("process should start alive", proc.isAlive)

        val killed = CliProcessRegistry.killAll()

        assertTrue("killAll should report killing at least the sleep process", killed >= 1)
        assertTrue("process should terminate", proc.waitFor(5, TimeUnit.SECONDS))
        assertFalse(proc.isAlive)
    }

    @Test fun `unregister removes a process so killAll ignores it`() {
        assumeFalse("uses unix sleep", isWindows)
        val proc = ProcessBuilder("sleep", "60").start()
        CliProcessRegistry.register(proc)
        CliProcessRegistry.unregister(proc)
        try {
            assertEquals(0, CliProcessRegistry.killAll())
            assertTrue("unregistered process must stay alive", proc.isAlive)
        } finally {
            proc.destroyForcibly()
        }
    }
}
