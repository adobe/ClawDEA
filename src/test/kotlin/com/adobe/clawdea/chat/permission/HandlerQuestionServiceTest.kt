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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for [HandlerQuestionService] — verifies the register /
 * owns / submit / cancel lifecycle without booting an IntelliJ application
 * (the constructor accepts a null project for that reason).
 */
class HandlerQuestionServiceTest {

    @Test
    fun `register issues a requestId with the handler-question prefix`() {
        val service = HandlerQuestionService()
        val id = service.register { /* ignored */ }
        assertTrue(
            "expected $id to start with ${HandlerQuestionService.HANDLER_QUESTION_PREFIX}",
            id.startsWith(HandlerQuestionService.HANDLER_QUESTION_PREFIX),
        )
        assertTrue("owns must claim the id it just minted", service.owns(id))
    }

    @Test
    fun `register issues a fresh id on every call`() {
        val service = HandlerQuestionService()
        val ids = (0..9).map { service.register {} }.toSet()
        assertEquals("each register call must produce a unique id", 10, ids.size)
    }

    @Test
    fun `owns rejects ids without the handler-question prefix`() {
        val service = HandlerQuestionService()
        assertFalse(service.owns(""))
        assertFalse(service.owns("perm-12345"))
        assertFalse(service.owns("foo:bar"))
    }

    @Test
    fun `submit invokes the resolver exactly once with the answers and freeforms`() {
        val service = HandlerQuestionService()
        var calls = 0
        var received: HandlerQuestionAnswers? = null
        val id = service.register { answers ->
            calls++
            received = answers
        }
        service.submit(
            id,
            answers = mapOf("Q1" to "Move"),
            freeforms = mapOf("Q1" to "docs/llm-wiki"),
        )
        assertEquals(1, calls)
        assertNotNull(received)
        assertEquals(mapOf("Q1" to "Move"), received?.answers)
        assertEquals(mapOf("Q1" to "docs/llm-wiki"), received?.freeforms)
    }

    @Test
    fun `submit removes the resolver — a second submit on the same id is a no-op`() {
        val service = HandlerQuestionService()
        var calls = 0
        val id = service.register { calls++ }
        service.submit(id, emptyMap(), emptyMap())
        service.submit(id, emptyMap(), emptyMap())
        assertEquals("resolver must only fire on the first submit", 1, calls)
    }

    @Test
    fun `submit on an unknown id is a silent no-op`() {
        val service = HandlerQuestionService()
        // Should not throw.
        service.submit("hq:does-not-exist", mapOf("q" to "a"), emptyMap())
    }

    @Test
    fun `cancel resolves with null and removes the resolver`() {
        val service = HandlerQuestionService()
        var calls = 0
        var received: HandlerQuestionAnswers? = HandlerQuestionAnswers(mapOf("placeholder" to "x"), emptyMap())
        val id = service.register { answers ->
            calls++
            received = answers
        }
        service.cancel(id)
        assertEquals(1, calls)
        assertNull("cancel must resolve with null", received)
        // Second cancel does nothing.
        service.cancel(id)
        assertEquals(1, calls)
    }

    @Test
    fun `cancel on an unknown id is a silent no-op`() {
        val service = HandlerQuestionService()
        service.cancel("hq:nope")
    }

    @Test
    fun `resolver that throws on submit does not corrupt the pending map`() {
        val service = HandlerQuestionService()
        val id1 = service.register { error("boom") }
        // Must not throw out to the caller.
        service.submit(id1, emptyMap(), emptyMap())
        // The second registration still works.
        var calls = 0
        val id2 = service.register { calls++ }
        service.submit(id2, emptyMap(), emptyMap())
        assertEquals(1, calls)
    }

    @Test
    fun `service implements the PermissionRequestHandler resolver interface`() {
        val service: PermissionRequestHandler.HandlerQuestionResolver = HandlerQuestionService()
        val id = (service as HandlerQuestionService).register {}
        assertTrue(service.owns(id))
        service.cancel(id)
    }
}
