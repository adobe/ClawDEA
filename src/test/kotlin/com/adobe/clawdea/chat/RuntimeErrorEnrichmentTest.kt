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
package com.adobe.clawdea.chat

import com.adobe.clawdea.provider.BackendKind
import org.junit.Assert.*
import org.junit.Test

class RuntimeErrorEnrichmentTest {

    @Test
    fun `enriches authentication errors`() {
        val result = ErrorEnricher.enrich("Error: unauthorized - invalid API key")
        assertNotNull(result)
        assertTrue(result!!.contains("Authentication failed"))
        assertTrue(result.contains("Settings"))
    }

    @Test
    fun `enriches 401 errors`() {
        val result = ErrorEnricher.enrich("HTTP 401: Unauthorized")
        assertNotNull(result)
        assertTrue(result!!.contains("Authentication failed"))
    }

    @Test
    fun `enriches rate limit errors`() {
        val result = ErrorEnricher.enrich("Error: rate limit exceeded (429)")
        assertNotNull(result)
        assertTrue(result!!.contains("Rate limited"))
    }

    @Test
    fun `enriches network errors`() {
        val result = ErrorEnricher.enrich("Error: ECONNREFUSED 127.0.0.1:443")
        assertNotNull(result)
        assertTrue(result!!.contains("Cannot reach"))
    }

    @Test
    fun `enriches connection timeout errors`() {
        val result = ErrorEnricher.enrich("connection timeout after 30000ms")
        assertNotNull(result)
        assertTrue(result!!.contains("Cannot reach"))
    }

    @Test
    fun `enriches overloaded errors`() {
        val result = ErrorEnricher.enrich("Error 529: API is overloaded")
        assertNotNull(result)
        assertTrue(result!!.contains("overloaded"))
    }

    @Test
    fun `returns null for unrecognized errors`() {
        val result = ErrorEnricher.enrich("Something completely unexpected happened")
        assertNull(result)
    }

    // --- OpenAI-compatible provider guidance: must never say "Claude API" ---

    private fun http(text: String) = ErrorEnricher.enrich(text, BackendKind.OPENAI_COMPATIBLE_HTTP)

    @Test
    fun `http auth error is provider-neutral and mentions the profile credential`() {
        val result = http("HTTP 401: Unauthorized")
        assertNotNull(result)
        assertTrue(result!!.contains("Authentication failed"))
        assertFalse(result.contains("Claude"))
    }

    @Test
    fun `http 403 forbidden is treated as an auth error`() {
        val result = http("HTTP 403: Access forbidden")
        assertNotNull(result)
        assertTrue(result!!.contains("Authentication failed"))
        assertFalse(result.contains("Claude"))
    }

    @Test
    fun `http rate limit mentions Retry-After`() {
        val result = http("Rate limit exceeded (429)")
        assertNotNull(result)
        assertTrue(result!!.contains("Retry-After"))
        assertFalse(result.contains("Claude"))
    }

    @Test
    fun `http timeout guidance is provider-neutral`() {
        val result = http("connection timeout after 30000ms")
        assertNotNull(result)
        assertTrue(result!!.contains("timed out"))
        assertFalse(result.contains("Claude"))
    }

    @Test
    fun `http connection error mentions base URL`() {
        val result = http("ECONNREFUSED 127.0.0.1:443")
        assertNotNull(result)
        assertTrue(result!!.contains("base URL"))
        assertFalse(result.contains("Claude"))
    }

    @Test
    fun `http partial-stream guidance is provider-neutral`() {
        val result = http("Request failed after partial output.")
        assertNotNull(result)
        assertTrue(result!!.contains("interrupted"))
        assertFalse(result.contains("Claude"))
    }

    @Test
    fun `http tool-limit guidance is provider-neutral`() {
        val result = http("Tool round limit exceeded")
        assertNotNull(result)
        assertTrue(result!!.contains("tool-call limit"))
        assertFalse(result.contains("Claude"))
    }

    @Test
    fun `http context-limit guidance is provider-neutral`() {
        val result = http("Context budget exceeded")
        assertNotNull(result)
        assertTrue(result!!.contains("context budget"))
        assertFalse(result.contains("Claude"))
    }

    @Test
    fun `http server error guidance is provider-neutral`() {
        val result = http("Server error")
        assertNotNull(result)
        assertTrue(result!!.contains("server error"))
        assertFalse(result.contains("Claude"))
    }

    @Test
    fun `http does not false-match rate limit on an incidental 429 in the detail tail`() {
        // The EHL/APC gateway wraps raw upstream SSE into a non-JSON error; the detail tail can
        // incidentally contain "429" (a token count / echoed header). It must NOT enrich to the
        // rate-limit guidance — only the primary message (before " — ") is matched.
        val result = http("""Upstream returned non-JSON response — data: {"x":429}""")
        assertNull(result)
    }

    @Test
    fun `http still enriches a genuine rate-limit primary message`() {
        val result = http("Rate limit exceeded — retry after 30s")
        assertNotNull(result)
        assertTrue(result!!.contains("Rate limited"))
        assertFalse(result.contains("Claude"))
    }

    @Test
    fun `http still enriches a genuine HTTP 429 primary message`() {
        val result = http("HTTP 429")
        assertNotNull(result)
        assertTrue(result!!.contains("Rate limited"))
    }
}
