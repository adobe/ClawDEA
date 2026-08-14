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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ProcessInterrupterTest {

    @Test
    fun `interrupt terminates a running process on this POSIX host`() {
        assumeTrue("POSIX /bin/sh required", File("/bin/sh").canExecute())
        val proc = ProcessBuilder("/bin/sh", "-c", "sleep 30").start()
        assertTrue("process should be running", proc.isAlive)

        val delivered = ProcessInterrupter.interrupt(proc)
        assertTrue("interrupt should be delivered on Unix", delivered)

        val exited = proc.waitFor(5, TimeUnit.SECONDS)
        assertTrue("process should exit promptly after SIGINT", exited)
        assertFalse(proc.isAlive)
    }
}
