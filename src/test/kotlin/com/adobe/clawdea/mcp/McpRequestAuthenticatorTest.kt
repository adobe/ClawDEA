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
package com.adobe.clawdea.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRequestAuthenticatorTest {

    private val token = "s3cr3t-token_ABC"
    private val auth = McpRequestAuthenticator(token)

    /** A well-behaved loopback CLI request: application/json, valid bearer, no browser headers. */
    private fun allowingCall(authorization: String? = "Bearer $token") = auth.authorize(
        origin = null,
        secFetchSite = null,
        contentType = "application/json",
        authorization = authorization,
        declaredContentLength = 1024,
    )

    @Test
    fun `a valid loopback request is allowed`() {
        assertEquals(McpRequestAuthenticator.Decision.Allow, allowingCall())
    }

    @Test
    fun `charset suffix on content type is accepted`() {
        val d = auth.authorize(null, null, "application/json; charset=utf-8", "Bearer $token", 10)
        assertEquals(McpRequestAuthenticator.Decision.Allow, d)
    }

    @Test
    fun `an Origin header is rejected (browser cross-origin)`() {
        val d = auth.authorize("https://evil.example", null, "application/json", "Bearer $token", 10)
        assertEquals(403, (d as McpRequestAuthenticator.Decision.Reject).status)
    }

    @Test
    fun `a Sec-Fetch-Site header is rejected`() {
        val d = auth.authorize(null, "cross-site", "application/json", "Bearer $token", 10)
        assertEquals(403, (d as McpRequestAuthenticator.Decision.Reject).status)
    }

    @Test
    fun `a non-json content type is rejected`() {
        val d = auth.authorize(null, null, "text/plain", "Bearer $token", 10)
        assertEquals(415, (d as McpRequestAuthenticator.Decision.Reject).status)
    }

    @Test
    fun `a missing content type is rejected`() {
        val d = auth.authorize(null, null, null, "Bearer $token", 10)
        assertEquals(415, (d as McpRequestAuthenticator.Decision.Reject).status)
    }

    @Test
    fun `an oversized declared body is rejected`() {
        val d = auth.authorize(
            null, null, "application/json", "Bearer $token",
            McpRequestAuthenticator.MAX_REQUEST_BODY_BYTES + 1,
        )
        assertEquals(413, (d as McpRequestAuthenticator.Decision.Reject).status)
    }

    @Test
    fun `a present but wrong token is rejected`() {
        val d = allowingCall(authorization = "Bearer wrong-token")
        assertEquals(401, (d as McpRequestAuthenticator.Decision.Reject).status)
    }

    @Test
    fun `a malformed authorization header is rejected`() {
        val d = allowingCall(authorization = token) // missing "Bearer " prefix
        assertEquals(401, (d as McpRequestAuthenticator.Decision.Reject).status)
    }

    @Test
    fun `an absent token is allowed (soft enforcement for clients that cannot forward it)`() {
        assertEquals(McpRequestAuthenticator.Decision.Allow, allowingCall(authorization = null))
    }

    @Test
    fun `when the server has no token a present header is not validated`() {
        // Defensive: an empty expected token must not turn every request into a 401.
        val open = McpRequestAuthenticator(expectedToken = "")
        val d = open.authorize(null, null, "application/json", "Bearer anything", 10)
        assertEquals(McpRequestAuthenticator.Decision.Allow, d)
    }

    @Test
    fun `transport checks run before the token check`() {
        // A wrong token AND a browser Origin: the Origin (403) must win over the token (401),
        // so the ordering that closes the reachable attack is not masked by the soft token.
        val d = auth.authorize("https://evil.example", null, "text/plain", "Bearer wrong", 10)
        assertTrue(d is McpRequestAuthenticator.Decision.Reject)
        assertEquals(403, (d as McpRequestAuthenticator.Decision.Reject).status)
    }
}
