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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for [PermissionRequestHandler.parseAnswers] and
 * [PermissionRequestHandler.parseFreeforms]. Both parsers are `internal`
 * companion functions (not `private`) so we can call them directly here
 * without faking out JCEF or the dispatcher — there is no public consumer
 * yet (Task 19b will wire one up for `/wiki-relocate`).
 */
class PermissionRequestHandlerParseAnswersTest {

    @Test
    fun `parseAnswers reads new shape with answers field`() {
        val payload = """{"answers":{"Q1":"A","Q2":"X, Y"},"freeforms":{"Q1":"ignored"}}"""
        val answers = PermissionRequestHandler.parseAnswers(payload)
        assertEquals(2, answers.size)
        assertEquals("A", answers["Q1"])
        assertEquals("X, Y", answers["Q2"])
    }

    @Test
    fun `parseAnswers tolerates legacy flat shape for CLI backward compat`() {
        val payload = """{"Which approach?":"Option A","Which features?":"Auth, Logging"}"""
        val answers = PermissionRequestHandler.parseAnswers(payload)
        assertEquals(2, answers.size)
        assertEquals("Option A", answers["Which approach?"])
        assertEquals("Auth, Logging", answers["Which features?"])
    }

    @Test
    fun `parseAnswers returns empty for blank or non-object payload`() {
        assertTrue(PermissionRequestHandler.parseAnswers("").isEmpty())
        assertTrue(PermissionRequestHandler.parseAnswers("   ").isEmpty())
        assertTrue(PermissionRequestHandler.parseAnswers("[]").isEmpty())
        assertTrue(PermissionRequestHandler.parseAnswers("not-json").isEmpty())
    }

    @Test
    fun `parseAnswers skips non-string values in the new shape`() {
        val payload = """{"answers":{"good":"yes","bad":123,"nested":{"x":1}}}"""
        val answers = PermissionRequestHandler.parseAnswers(payload)
        assertEquals(1, answers.size)
        assertEquals("yes", answers["good"])
    }

    @Test
    fun `parseFreeforms reads freeforms field`() {
        val payload = """{"answers":{"Q1":"A"},"freeforms":{"Q1":"docs/llm-wiki","Q2":""}}"""
        val freeforms = PermissionRequestHandler.parseFreeforms(payload)
        assertEquals(2, freeforms.size)
        assertEquals("docs/llm-wiki", freeforms["Q1"])
        assertEquals("", freeforms["Q2"])
    }

    @Test
    fun `parseFreeforms returns empty when freeforms field absent`() {
        // Legacy flat shape: no freeforms anywhere.
        assertTrue(PermissionRequestHandler.parseFreeforms("""{"Q1":"A"}""").isEmpty())
        // New shape with only answers, no freeforms field.
        assertTrue(PermissionRequestHandler.parseFreeforms("""{"answers":{"Q1":"A"}}""").isEmpty())
        assertTrue(PermissionRequestHandler.parseFreeforms("").isEmpty())
        assertTrue(PermissionRequestHandler.parseFreeforms("[]").isEmpty())
        assertTrue(PermissionRequestHandler.parseFreeforms("not-json").isEmpty())
    }
}
