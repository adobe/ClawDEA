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

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.TimeUnit

/**
 * Determines subscription auth status.
 *
 * The CLI is the source of truth: `claude auth status --json` returns either
 *   `{"loggedIn":bool,"authMethod":"claude.ai","apiProvider":"firstParty","email":"…","subscriptionType":"…"}`
 * (subscription) or `{"authMethod":"third_party","apiProvider":"bedrock"}` (3P).
 *
 * To see the subscription state regardless of any Bedrock/Vertex env vars the
 * user has exported, we strip `CLAUDE_CODE_USE_BEDROCK` / `CLAUDE_CODE_USE_VERTEX`
 * from the subprocess environment before asking the CLI.
 *
 * Injectable `cliPath` and `timeoutMillis` keep the probe unit-testable.
 */
class SubscriptionAuthProbe(
    private val cliPath: String = "claude",
    private val timeoutMillis: Long = 3000,
) {
    private val log = Logger.getInstance(SubscriptionAuthProbe::class.java)

    fun probe(): AuthStatus {
        if (cliPath.isBlank()) return AuthStatus.Unknown

        val result = try {
            runProcess(listOf(cliPath, "auth", "status", "--json"))
        } catch (e: Exception) {
            log.warn("auth status subprocess failed to start", e)
            return AuthStatus.Unknown
        }

        if (result.timedOut) {
            log.info("auth status subprocess timed out")
            return AuthStatus.Unknown
        }

        if (result.exit != 0) {
            val combined = (result.stdout + "\n" + result.stderr).lowercase()
            val looksLikeAuthError = AUTH_ERROR_HINTS.any { combined.contains(it) }
            return if (looksLikeAuthError) {
                AuthStatus.Invalid(reason = firstNonBlankLine(result.stderr, result.stdout))
            } else {
                AuthStatus.Unknown
            }
        }

        val loggedIn = extractJsonBooleanValue(result.stdout, "loggedIn") ?: false
        val authMethod = extractJsonStringValue(result.stdout, "authMethod")
        // Only `claude.ai` / `claudeai` means a real subscription; `third_party`
        // means Bedrock/Vertex and is not a subscription sign-in from our POV.
        val isSubscription = loggedIn && authMethod?.lowercase()?.let {
            it == "claude.ai" || it == "claudeai"
        } == true

        return if (isSubscription) {
            AuthStatus.SignedIn(
                tier = extractJsonStringValue(result.stdout, "subscriptionType"),
                email = extractJsonStringValue(result.stdout, "email"),
            )
        } else {
            AuthStatus.NotSignedIn
        }
    }

    // Callers assume the subprocess produces small (KB-scale) output — reading
    // stdout/stderr after waitFor is safe for that. Do NOT reuse for commands
    // that may produce large output: doing so can deadlock on the OS pipe buffer.
    private fun runProcess(command: List<String>): ProcessResult {
        val pb = ProcessBuilder(command).redirectErrorStream(false)
        // The subscription state must be read independently of any 3P-provider
        // env vars the user may have exported globally.
        pb.environment().remove("CLAUDE_CODE_USE_BEDROCK")
        pb.environment().remove("CLAUDE_CODE_USE_VERTEX")
        val proc = pb.start()
        val exited = proc.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!exited) {
            proc.destroyForcibly()
            return ProcessResult(exit = 0, stdout = "", stderr = "", timedOut = true)
        }
        return ProcessResult(
            exit = proc.exitValue(),
            stdout = proc.inputStream.bufferedReader().readText(),
            stderr = proc.errorStream.bufferedReader().readText(),
            timedOut = false,
        )
    }

    private fun firstNonBlankLine(vararg candidates: String): String {
        for (c in candidates) {
            for (line in c.lines()) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) return trimmed.take(MAX_REASON_LEN)
            }
        }
        return "authentication failed"
    }

    /**
     * Minimal JSON boolean-value extractor. Accepts `"key":true` / `"key":false`
     * with optional whitespace. Returns null if the key is missing or its value
     * is not a plain boolean literal.
     */
    private fun extractJsonBooleanValue(text: String, key: String): Boolean? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)\\b")
        return pattern.find(text)?.groupValues?.get(1)?.let { it == "true" }
    }

    /**
     * Minimal JSON string-value extractor. Accepts `"key":"value"` with optional
     * whitespace. Does not handle escaped quotes — good enough for the simple
     * `auth status` payload (email, orgName, subscriptionType).
     */
    private fun extractJsonStringValue(text: String, key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"\\\\]*)\"")
        return pattern.find(text)?.groupValues?.get(1)
    }

    private data class ProcessResult(
        val exit: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )

    companion object {
        private const val MAX_REASON_LEN = 200

        private val AUTH_ERROR_HINTS = listOf(
            "unauthorized",
            "authentication",
            "auth error",
            "invalid token",
            "credentials expired",
            "subscription expired",
            "not logged in",
        )
    }
}
