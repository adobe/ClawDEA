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
package com.adobe.clawdea.chat.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PermissionDispatcherTest {

    @Test
    fun `submit blocks until decision is posted from another thread`() {
        val posted = CountDownLatch(1)
        lateinit var capturedId: String
        val renderRecord = mutableListOf<PermissionRequest>()
        val dispatcher = PermissionDispatcher(onRender = { req ->
            renderRecord.add(req)
            capturedId = req.requestId
            posted.countDown()
        })

        val thread = Thread {
            val result = dispatcher.submit("Bash", """{"command":"ls"}""")
            assertEquals(PermissionRequest.Decision.ALLOW, result.decision)
            assertNull("regular allow does not carry an updated input", result.updatedInput)
        }
        thread.start()

        assertTrue(posted.await(2, TimeUnit.SECONDS))
        dispatcher.resolve(capturedId, PermissionRequest.Decision.ALLOW)
        thread.join(2_000)
        assertTrue(renderRecord.size == 1)
    }

    @Test
    fun `submit returns DENY when the thread is interrupted`() {
        val dispatcher = PermissionDispatcher(onRender = { _ -> /* never resolves */ })
        var result: PermissionDispatcher.Result? = null
        val thread = Thread {
            result = dispatcher.submit("Bash", """{"command":"ls"}""")
        }
        thread.start()
        Thread.sleep(50)
        thread.interrupt()
        thread.join(2_000)
        assertEquals(PermissionRequest.Decision.DENY, result?.decision)
    }

    @Test
    fun `submit returns the updated input supplied by resolve`() {
        val rendered = CountDownLatch(1)
        lateinit var capturedId: String
        val dispatcher = PermissionDispatcher(onRender = { req ->
            capturedId = req.requestId
            rendered.countDown()
        })
        var result: PermissionDispatcher.Result? = null
        val thread = Thread {
            result = dispatcher.submit("AskUserQuestion", """{"questions":[]}""")
        }
        thread.start()
        assertTrue(rendered.await(2, TimeUnit.SECONDS))
        dispatcher.resolve(
            capturedId,
            PermissionRequest.Decision.ALLOW,
            updatedInput = """{"questions":[],"answers":{"q":"a"}}""",
        )
        thread.join(2_000)
        assertEquals(PermissionRequest.Decision.ALLOW, result?.decision)
        assertEquals("""{"questions":[],"answers":{"q":"a"}}""", result?.updatedInput)
    }

    @Test
    fun `resolve with unknown id is a no-op`() {
        val dispatcher = PermissionDispatcher(onRender = { _ -> /* render no-op */ })
        dispatcher.resolve("missing", PermissionRequest.Decision.ALLOW) // must not throw
    }

    @Test
    fun `duplicate resolve calls keep the first decision`() {
        val renderCompleted = CountDownLatch(1)
        var capturedReq: PermissionRequest? = null
        val dispatcher = PermissionDispatcher(onRender = { req ->
            capturedReq = req
            renderCompleted.countDown()
        })
        val thread = Thread {
            dispatcher.submit("Bash", """{"command":"ls"}""")
        }
        thread.start()

        assertTrue(renderCompleted.await(2, TimeUnit.SECONDS))
        val id = capturedReq!!.requestId
        dispatcher.resolve(id, PermissionRequest.Decision.ALLOW)
        dispatcher.resolve(id, PermissionRequest.Decision.DENY) // should be ignored
        thread.join(2_000)
        assertEquals(PermissionRequest.Decision.ALLOW, capturedReq!!.decision)
    }

    @Test
    fun `after resolve the request is no longer looked up`() {
        val renderCompleted = CountDownLatch(1)
        var capturedReq: PermissionRequest? = null
        val dispatcher = PermissionDispatcher(onRender = { req ->
            capturedReq = req
            renderCompleted.countDown()
        })
        val thread = Thread { dispatcher.submit("Bash", """{"command":"ls"}""") }
        thread.start()
        assertTrue(renderCompleted.await(2, TimeUnit.SECONDS))
        val id = capturedReq!!.requestId
        dispatcher.resolve(id, PermissionRequest.Decision.ALLOW)
        thread.join(2_000)

        assertNull(dispatcher.peek(id))
    }

    @Test
    fun `notifyAutoAllowed is non-blocking, pre-resolves the request, and calls onAutoAllowed`() {
        var submitted = false
        var autoNotified: PermissionRequest? = null
        val dispatcher = PermissionDispatcher(
            onRender = { _ -> submitted = true },
            onAutoAllowed = { req -> autoNotified = req },
        )
        dispatcher.notifyAutoAllowed("Bash", """{"command":"ls"}""")
        assertFalse("onRender must not fire on auto-allow", submitted)
        val captured = autoNotified!!
        assertEquals("Bash", captured.toolName)
        assertEquals(PermissionRequest.Decision.ALLOW, captured.decision)
        assertEquals(0L, captured.latch.count)
        assertNull("auto-allowed requests are not tracked in flight", dispatcher.peek(captured.requestId))
    }
}
