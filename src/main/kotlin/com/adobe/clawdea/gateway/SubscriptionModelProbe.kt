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
package com.adobe.clawdea.gateway

import com.adobe.clawdea.CLAUDE_DIR
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Fetches the live subscription model catalog from the Anthropic Messages API
 * using the first-party OAuth bearer token that `claude auth login --claudeai`
 * persists. Linux writes it to `~/.claude/.credentials.json`; macOS stores it
 * in the login Keychain under service `Claude Code-credentials` keyed by the
 * macOS user. The probe tries the file first, then the Keychain when on macOS.
 *
 * Falls back to null on any IO / parse failure — `ModelSelectorProbeStarter`
 * leaves the persisted catalog untouched in that case.
 */
class SubscriptionModelProbe(
    private val credentialsFile: File = defaultCredentialsFile(),
    private val timeoutMs: Int = 5000,
    private val tokenSource: () -> String? = { defaultTokenSource(credentialsFile) },
) : ModelCatalogProbe {

    private val log = Logger.getInstance(SubscriptionModelProbe::class.java)

    override fun probe(): List<ModelEntry>? {
        val token = tokenSource() ?: run {
            log.info("subscription probe: no OAuth token available (file or keychain)")
            return null
        }
        return try {
            val conn = URI("https://api.anthropic.com/v1/models").toURL().openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.setRequestProperty("anthropic-beta", "oauth-2025-04-20")
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) {
                    log.info("subscription probe: http ${conn.responseCode}")
                    return null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                AnthropicModelProbe.parseModelsJson(body)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            log.info("subscription probe: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    companion object {
        internal const val KEYCHAIN_SERVICE: String = "Claude Code-credentials"

        internal fun defaultCredentialsFile(): File =
            File(System.getProperty("user.home"), "$CLAUDE_DIR/.credentials.json")

        private fun defaultTokenSource(file: File): String? {
            readAccessToken(file)?.let { return it }
            if (!isMac()) return null
            val account = System.getProperty("user.name").orEmpty()
            if (account.isBlank()) return null
            return readAccessTokenFromKeychain(KEYCHAIN_SERVICE, account)
        }

        private fun isMac(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("mac")

        /**
         * Extracts `claudeAiOauth.accessToken` from the credentials file.
         * Returns null if the file is missing, unreadable, malformed, or lacks the token.
         */
        internal fun readAccessToken(file: File): String? {
            if (!file.isFile || file.length() == 0L) return null
            return try {
                parseAccessTokenFromJson(file.readText())
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Shells out to `/usr/bin/security find-generic-password -s <service>
         * -a <account> -w` to read the JSON blob Claude Code stores in the
         * macOS Keychain, then extracts `claudeAiOauth.accessToken` from it.
         * Returns null on any failure; does not prompt.
         */
        internal fun readAccessTokenFromKeychain(
            service: String,
            account: String,
            timeoutMs: Long = 3_000,
        ): String? {
            val proc = try {
                ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", service, "-a", account, "-w")
                    .redirectErrorStream(false)
                    .start()
            } catch (_: Exception) {
                return null
            }
            return try {
                if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                    return null
                }
                if (proc.exitValue() != 0) return null
                val stdout = proc.inputStream.bufferedReader().readText().trim()
                if (stdout.isEmpty()) null else parseAccessTokenFromJson(stdout)
            } catch (_: Exception) {
                null
            }
        }

        internal fun parseAccessTokenFromJson(json: String): String? {
            if (json.isBlank()) return null
            return try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonObject) return null
                val oauth = root.asJsonObject.get("claudeAiOauth")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?: return null
                oauth.get("accessToken")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
