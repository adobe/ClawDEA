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

import com.adobe.clawdea.settings.ClawDEASettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OpenAIAuthProvider(
    private val apiKey: () -> String,
    private val envApiKey: () -> String?,
    private val httpClient: HttpClient = defaultHttpClient,
    private val apiBaseUrl: String = "https://api.openai.com",
) : AuthProvider {

    override val id = "openai"

    constructor() : this(
        apiKey = { ClawDEASettings.getInstance().getOpenAIApiKey() },
        envApiKey = { System.getenv("OPENAI_API_KEY") },
    )

    constructor(apiKey: String, envApiKey: String?) : this(
        apiKey = { apiKey },
        envApiKey = { envApiKey },
    )

    fun getApiKey(): String? {
        val env = envApiKey()
        if (!env.isNullOrBlank()) return env
        val key = apiKey()
        if (key.isNotBlank()) return key
        return null
    }

    override fun isConfigured(): Boolean = getApiKey() != null

    override fun applyToEnvironment(env: MutableMap<String, String>) {
        val key = getApiKey() ?: return
        env["OPENAI_API_KEY"] = key
    }

    override fun validate(): AuthValidation =
        if (isConfigured()) {
            AuthValidation(valid = true, message = null)
        } else {
            AuthValidation(
                valid = false,
                message = "No OpenAI API key configured. Set it in Settings > Tools > ClawDEA, or export OPENAI_API_KEY in your shell.",
            )
        }

    override fun testConnection(): ConnectionTestResult {
        val key = getApiKey()
            ?: return ConnectionTestResult(false, "No API key configured.")

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$apiBaseUrl/v1/models"))
            .header("Authorization", "Bearer $key")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val start = System.currentTimeMillis()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val latency = System.currentTimeMillis() - start
            when (response.statusCode()) {
                200 -> ConnectionTestResult(true, "Connected (${latency}ms)", latency)
                401 -> ConnectionTestResult(false, "Authentication failed (401). Check your API key.", latency)
                403 -> ConnectionTestResult(false, "Access denied (403). Your API key may lack permissions.", latency)
                429 -> ConnectionTestResult(true, "Connected but rate limited (429). Try again shortly.", latency)
                else -> ConnectionTestResult(false, "API returned HTTP ${response.statusCode()}", latency)
            }
        } catch (e: java.net.ConnectException) {
            ConnectionTestResult(false, "Connection refused. Check your network.")
        } catch (e: java.net.http.HttpTimeoutException) {
            ConnectionTestResult(false, "Connection timed out after 15s.")
        } catch (e: Exception) {
            ConnectionTestResult(false, "Connection failed: ${e.message}")
        }
    }

    companion object {
        private val defaultHttpClient: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }
}
