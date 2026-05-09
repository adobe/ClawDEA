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
package com.adobe.clawdea.auth

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnthropicAuthProviderTest {
    @Test fun `isConfigured returns true when apiKey is set`() {
        assertTrue(AnthropicAuthProvider(apiKey = "sk-test", envApiKey = null).isConfigured())
    }
    @Test fun `isConfigured returns true when env var is set`() {
        assertTrue(AnthropicAuthProvider(apiKey = "", envApiKey = "sk-env").isConfigured())
    }
    @Test fun `isConfigured returns false when both empty`() {
        assertFalse(AnthropicAuthProvider(apiKey = "", envApiKey = null).isConfigured())
    }
    @Test fun `getApiKey prefers env var over settings`() {
        assertEquals("sk-env", AnthropicAuthProvider(apiKey = "sk-settings", envApiKey = "sk-env").getApiKey())
    }
    @Test fun `getApiKey falls back to settings`() {
        assertEquals("sk-settings", AnthropicAuthProvider(apiKey = "sk-settings", envApiKey = null).getApiKey())
    }
    @Test fun `getApiKey returns null when both empty`() {
        assertNull(AnthropicAuthProvider(apiKey = "", envApiKey = null).getApiKey())
    }
    @Test fun `applyToEnvironment sets ANTHROPIC_API_KEY`() {
        val env = mutableMapOf<String, String>()
        AnthropicAuthProvider(apiKey = "sk-test", envApiKey = null).applyToEnvironment(env)
        assertEquals("sk-test", env["ANTHROPIC_API_KEY"])
    }
    @Test fun `applyToEnvironment does not set key when empty`() {
        val env = mutableMapOf<String, String>()
        AnthropicAuthProvider(apiKey = "", envApiKey = null).applyToEnvironment(env)
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"))
    }
    @Test fun `validate succeeds when configured`() {
        assertTrue(AnthropicAuthProvider(apiKey = "sk-test", envApiKey = null).validate().valid)
    }
    @Test fun `validate fails when not configured`() {
        val r = AnthropicAuthProvider(apiKey = "", envApiKey = null).validate()
        assertFalse(r.valid)
        assertTrue(r.message!!.contains("API key"))
    }
    @Test fun `testConnection fails when no key configured`() {
        val r = AnthropicAuthProvider(apiKey = "", envApiKey = null).testConnection()
        assertFalse(r.success)
        assertTrue(r.message.contains("No API key"))
    }
    @Test fun `testConnection reports success on 200`() {
        withMockApi(200, """{"id":"msg_1","type":"message","content":[]}""") { baseUrl, client ->
            val provider = AnthropicAuthProvider(
                apiKey = { "sk-test" },
                envApiKey = { null },
                httpClient = client,
                apiBaseUrl = baseUrl,
            )
            val r = provider.testConnection()
            assertTrue(r.success)
            assertTrue(r.latencyMs >= 0)
        }
    }
    @Test fun `testConnection reports auth failure on 401`() {
        withMockApi(401, """{"error":"invalid_api_key"}""") { baseUrl, client ->
            val provider = AnthropicAuthProvider(
                apiKey = { "sk-bad" },
                envApiKey = { null },
                httpClient = client,
                apiBaseUrl = baseUrl,
            )
            val r = provider.testConnection()
            assertFalse(r.success)
            assertTrue(r.message.contains("401"))
        }
    }
    @Test fun `testConnection reports rate limited on 429 as success`() {
        withMockApi(429, """{"error":"rate_limited"}""") { baseUrl, client ->
            val provider = AnthropicAuthProvider(
                apiKey = { "sk-test" },
                envApiKey = { null },
                httpClient = client,
                apiBaseUrl = baseUrl,
            )
            val r = provider.testConnection()
            assertTrue(r.success)
            assertTrue(r.message.contains("rate limited"))
        }
    }

    private fun withMockApi(statusCode: Int, body: String, block: (String, HttpClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/messages") { exchange ->
            exchange.sendResponseHeaders(statusCode, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val port = server.address.port
        val baseUrl = "http://127.0.0.1:$port"
        try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
            block(baseUrl, client)
        } finally {
            server.stop(0)
        }
    }
}
