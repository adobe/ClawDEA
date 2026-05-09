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

import org.junit.Assert.*
import org.junit.Test

class CliEventParserAuthFailureTest {

    private val parser = CliEventParser()

    @Test
    fun `detects system error with authentication error subtype`() {
        val json = """{"type":"system","subtype":"error","error":{"type":"authentication_error","message":"invalid token"}}"""
        val event = parser.parse(json)
        assertTrue("expected AuthFailure, got $event", event is CliEvent.AuthFailure)
        assertEquals("invalid token", (event as CliEvent.AuthFailure).reason)
    }

    @Test
    fun `detects subscription_expired subtype`() {
        val json = """{"type":"system","subtype":"error","error":{"type":"subscription_expired","message":"renew your subscription"}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.AuthFailure)
    }

    @Test
    fun `detects unauthorized subtype`() {
        val json = """{"type":"system","subtype":"error","error":{"type":"unauthorized","message":"401"}}"""
        val event = parser.parse(json)
        assertTrue(event is CliEvent.AuthFailure)
    }

    @Test
    fun `detects result with is_error true and auth-sounding message`() {
        val json = """{"type":"result","result":"Error: your credentials have expired, please log in","is_error":true,"total_cost_usd":0.0,"session_id":"s1"}"""
        val event = parser.parse(json)
        assertTrue("expected AuthFailure, got $event", event is CliEvent.AuthFailure)
    }

    @Test
    fun `does not trigger on unrelated system errors`() {
        val json = """{"type":"system","subtype":"error","error":{"type":"rate_limit","message":"slow down"}}"""
        val event = parser.parse(json)
        assertFalse("rate_limit must not be classified as auth failure", event is CliEvent.AuthFailure)
    }

    @Test
    fun `does not trigger on result error that mentions auth unrelatedly`() {
        // Must contain zero auth hints — a filename that happens to include "authors" shouldn't trip.
        val json = """{"type":"result","result":"Error: file authors.md not found","is_error":true,"total_cost_usd":0.0,"session_id":"s1"}"""
        val event = parser.parse(json)
        assertFalse(event is CliEvent.AuthFailure)
    }
}
