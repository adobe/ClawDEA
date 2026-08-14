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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Validates inbound MCP HTTP requests before they reach tool dispatch.
 *
 * Binding to `127.0.0.1` on a random port is a discovery obstacle, not access control: a web page
 * the user visits can issue a CORS "simple" POST to `http://127.0.0.1:<port>/mcp` (no preflight),
 * and while it cannot read the response, the side effect still lands — `propose_write` (with
 * auto-accept), `get_diagnostics` spawning the project's build, `debug_evaluate` in a live debuggee.
 *
 * Two layers close this:
 *
 *  - **Layer A (transport, universal).** Reject any request carrying a browser-only header
 *    (`Origin` / `Sec-Fetch-Site`) and require `Content-Type: application/json`. A cross-origin
 *    fetch cannot both omit `Origin` and send `application/json` without triggering a preflight
 *    this server never satisfies, so the reachable browser vector is closed for every client. The
 *    loopback CLIs send neither `Origin` nor a browser content type, so they are unaffected.
 *
 *  - **Layer B (bearer token, soft).** A per-instance token handed to Claude-family clients via
 *    their MCP config `headers`. A present-but-wrong token is rejected (tamper evidence); an
 *    *absent* token is allowed, because the Codex app-server transport cannot be verified to
 *    forward a configured header and must not be broken. Layer A already covers the browser attack
 *    regardless, so the soft token is pure defense-in-depth on the Claude path.
 *
 * A declared-body-size cap bounds memory against a hostile POST before the body is read.
 *
 * Pure and free of `com.sun.net.httpserver` types so it can be unit-tested directly; [McpServer]
 * extracts the header values from the exchange and calls [authorize].
 */
internal class McpRequestAuthenticator(private val expectedToken: String) {

    sealed interface Decision {
        object Allow : Decision
        data class Reject(val status: Int, val message: String) : Decision
    }

    fun authorize(
        origin: String?,
        secFetchSite: String?,
        contentType: String?,
        authorization: String?,
        declaredContentLength: Long,
    ): Decision {
        if (origin != null || secFetchSite != null) {
            return Decision.Reject(403, "Cross-origin requests are not permitted")
        }
        if (contentType == null || !contentType.trim().lowercase().startsWith("application/json")) {
            return Decision.Reject(415, "Content-Type must be application/json")
        }
        if (declaredContentLength > MAX_REQUEST_BODY_BYTES) {
            return Decision.Reject(413, "Request body exceeds $MAX_REQUEST_BODY_BYTES bytes")
        }
        // Soft token: only meaningful when the server has a token AND the client sent one. An absent
        // header is allowed so a client that cannot forward it (Codex app-server) is not broken.
        if (expectedToken.isNotEmpty() && authorization != null && !tokenMatches(authorization)) {
            return Decision.Reject(401, "Invalid bearer token")
        }
        return Decision.Allow
    }

    private fun tokenMatches(authorizationHeader: String): Boolean {
        val prefix = "Bearer "
        if (!authorizationHeader.startsWith(prefix)) return false
        val presented = authorizationHeader.substring(prefix.length).trim()
        // Constant-time compare so a wrong token cannot be recovered by timing.
        return MessageDigest.isEqual(
            presented.toByteArray(StandardCharsets.UTF_8),
            expectedToken.toByteArray(StandardCharsets.UTF_8),
        )
    }

    companion object {
        const val MAX_REQUEST_BODY_BYTES = 64L * 1024 * 1024
    }
}
