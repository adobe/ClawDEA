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
}
