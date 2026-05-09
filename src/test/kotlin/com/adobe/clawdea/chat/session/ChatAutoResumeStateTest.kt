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
package com.adobe.clawdea.chat.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ChatAutoResumeStateTest {

    @Test
    fun `consumeIfUnused returns true on first call`() {
        val state = ChatAutoResumeState()
        assertTrue(state.consumeIfUnused())
    }

    @Test
    fun `consumeIfUnused returns false on subsequent calls`() {
        val state = ChatAutoResumeState()
        state.consumeIfUnused()
        assertFalse(state.consumeIfUnused())
        assertFalse(state.consumeIfUnused())
    }

    @Test
    fun `concurrent callers see exactly one true result`() {
        val state = ChatAutoResumeState()
        val threadCount = 32
        val trueCount = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            repeat(threadCount) {
                pool.submit {
                    start.await()
                    if (state.consumeIfUnused()) trueCount.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            done.await()
            assertEquals(1, trueCount.get())
        } finally {
            pool.shutdown()
        }
    }
}
