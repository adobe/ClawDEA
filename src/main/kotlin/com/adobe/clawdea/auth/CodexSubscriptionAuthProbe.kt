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
 * Determines OpenAI ChatGPT (codex) subscription auth status.
 *
 * The codex CLI is the source of truth: `codex login status` prints plain text —
 * `Logged in using ChatGPT` (signed in) or `Not logged in` (signed out). Unlike
 * `claude auth status` there is no `--json` and no email/tier in the output, so a
 * successful sign-in maps to `AuthStatus.SignedIn(tier = null, email = null)`.
 *
 * Parsing is order-sensitive: "not logged in" contains "logged in" as a substring, so
 * the negative case is checked first.
 *
 * Injectable `cliPath` and `timeoutMillis` keep the probe unit-testable.
 */
class CodexSubscriptionAuthProbe(
    private val cliPath: String = "codex",
    private val timeoutMillis: Long = 3000,
) {
    private val log = Logger.getInstance(CodexSubscriptionAuthProbe::class.java)

    fun probe(): AuthStatus {
        if (cliPath.isBlank()) return AuthStatus.Unknown

        val result = try {
            runProcess(listOf(cliPath, "login", "status"))
        } catch (e: Exception) {
            log.warn("codex login status subprocess failed to start", e)
            return AuthStatus.Unknown
        }

        if (result.timedOut) {
            log.info("codex login status subprocess timed out")
            return AuthStatus.Unknown
        }

        return parseLoginStatus(result.stdout, result.stderr, result.exit)
    }

    // Callers assume the subprocess produces small (KB-scale) output — reading
    // stdout/stderr after waitFor is safe for that. Do NOT reuse for commands
    // that may produce large output: doing so can deadlock on the OS pipe buffer.
    private fun runProcess(command: List<String>): ProcessResult {
        val pb = ProcessBuilder(command).redirectErrorStream(false)
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
            "auth error",
            "invalid token",
            "credentials expired",
        )

        /**
         * Maps `codex login status` output to an [AuthStatus]. Order-sensitive: "not logged in"
         * contains "logged in" as a substring, so the negative case is matched first. A non-zero
         * exit with an auth-error hint maps to [AuthStatus.Invalid]; anything else is [Unknown].
         */
        internal fun parseLoginStatus(stdout: String, stderr: String, exit: Int): AuthStatus {
            val combined = (stdout + "\n" + stderr).lowercase()
            return when {
                combined.contains("not logged in") || combined.contains("not authenticated") -> AuthStatus.NotSignedIn
                combined.contains("logged in") || combined.contains("authenticated") ->
                    AuthStatus.SignedIn(tier = null, email = null)
                exit != 0 && AUTH_ERROR_HINTS.any { combined.contains(it) } ->
                    AuthStatus.Invalid(reason = firstNonBlankLine(stderr, stdout))
                else -> AuthStatus.Unknown
            }
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
    }
}
