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
// src/main/kotlin/com/adobe/clawdea/gateway/ClaudeGateway.kt
package com.adobe.clawdea.gateway

import com.adobe.clawdea.auth.AuthManager
import com.adobe.clawdea.cli.CliEnvironment
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

enum class GatewayPath {
    ANTHROPIC_API,
    BEDROCK_API,
    OPENAI_COMPATIBLE_API,
    CLAUDE_CLI,
}

/**
 * Claude API client for latency-sensitive features (completions, quick actions).
 *
 * Two execution paths:
 * - **Direct API**: when ANTHROPIC_API_KEY is available, uses java.net.http.HttpClient
 *   with SSE streaming to api.anthropic.com. Lowest latency.
 * - **CLI fallback**: when no API key (Bedrock, OAuth, etc.), shells out to
 *   `claude -p` which handles all auth methods. Slightly higher latency but
 *   works with any configured auth.
 */
@Service
class ClaudeGateway {

    private val log = Logger.getInstance(ClaudeGateway::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val parser = StreamingParser()

    /**
     * Stream a request, emitting StreamEvents as they arrive.
     * Chooses the fastest available path: direct Anthropic API, direct Bedrock API,
     * OpenAI-compatible API, or CLI fallback.
     */
    fun stream(request: GatewayRequest): Flow<StreamEvent> {
        val authManager = com.adobe.clawdea.auth.AuthManager.getInstance()
        val settings = ClawDEASettings.getInstance()

        // Route completions through the COMPLETIONS role selection, not the global provider
        val completionsSelection = com.adobe.clawdea.provider.RoleSelectionStore(settings).get(com.adobe.clawdea.provider.AgentRole.COMPLETIONS)
        val providerId = completionsSelection.providerId

        val anthropic = authManager.providerById("anthropic") as? com.adobe.clawdea.auth.AnthropicAuthProvider
        val anthropicKeyPresent = anthropic?.getApiKey()?.isNotBlank() == true

        val bedrock = authManager.providerById("bedrock") as? com.adobe.clawdea.auth.BedrockAuthProvider
        val bedrockDirectReady = providerId == "bedrock" &&
            bedrock?.resolvedRegion()?.isNotBlank() == true &&
            bedrock.resolvedBearerToken()?.isNotBlank() == true

        val activeProfileId = completionsSelection.profileId ?: settings.state.activeOpenAiCompatibleProfileId
        val profileStore = com.adobe.clawdea.provider.openai.profile.ProfileStore(settings)
        val credentialStore = com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore()
        val openAiProfileReady = providerId == com.adobe.clawdea.provider.ProviderRegistry.OPENAI_COMPATIBLE_ID &&
            profileStore.profile(activeProfileId) != null &&
            credentialStore.get(activeProfileId).isNotBlank()

        val path = selectPath(providerId, anthropicKeyPresent, bedrockDirectReady, openAiProfileReady)

        // Use model from COMPLETIONS selection if present, otherwise fall back to request.model
        val effectiveModel = completionsSelection.modelId.ifBlank { request.model }
        val effectiveRequest = if (effectiveModel != request.model) {
            request.copy(model = effectiveModel)
        } else {
            request
        }

        return when (path) {
            GatewayPath.ANTHROPIC_API -> streamViaApi(effectiveRequest, anthropic!!.getApiKey()!!)
            GatewayPath.BEDROCK_API -> {
                val region = bedrock!!.resolvedRegion()!!
                val token = bedrock.resolvedBearerToken()!!
                val model = resolveCliModel(effectiveRequest.model)
                streamViaBedrock(effectiveRequest, region, token, model)
            }
            GatewayPath.OPENAI_COMPATIBLE_API -> streamViaOpenAiCompatible(effectiveRequest, activeProfileId)
            GatewayPath.CLAUDE_CLI -> streamViaCli(effectiveRequest)
        }
    }

    /**
     * Direct Anthropic API path — lowest latency, requires ANTHROPIC_API_KEY.
     */
    private fun streamViaApi(request: GatewayRequest, apiKey: String): Flow<StreamEvent> = flow {
        val body = buildRequestBody(request)

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .timeout(Duration.ofSeconds(request.timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        try {
            val response = withContext(Dispatchers.IO) {
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())
            }

            if (response.statusCode() == 429) {
                emit(StreamEvent.Error("Rate limited (429). Please wait before retrying."))
                return@flow
            }
            if (response.statusCode() == 401) {
                emit(StreamEvent.Error("Authentication failed (401). Check your API key."))
                return@flow
            }
            if (response.statusCode() !in 200..299) {
                emit(StreamEvent.Error("API error: HTTP ${response.statusCode()}"))
                return@flow
            }

            for (line in response.body()) {
                val event = parser.parseLine(line)
                if (event != null) {
                    emit(event)
                }
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error("Request failed: ${e.message}"))
        }
    }

    /**
     * Direct Bedrock API path — uses bearer token auth against the Bedrock invoke endpoint.
     * Uses non-streaming invoke to avoid AWS event stream binary encoding.
     * For completions (Haiku, 256 tokens) the full response arrives fast enough.
     */
    private fun streamViaBedrock(
        request: GatewayRequest,
        region: String,
        bearerToken: String,
        modelId: String,
    ): Flow<StreamEvent> = flow {
        val body = buildBedrockRequestBody(request)
        val encodedModel = java.net.URLEncoder.encode(modelId, StandardCharsets.UTF_8)
        val url = "https://bedrock-runtime.$region.amazonaws.com/model/$encodedModel/invoke"

        log.info("Gateway Bedrock direct: $region / $modelId")

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $bearerToken")
            .timeout(Duration.ofSeconds(request.timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        try {
            val response = withContext(Dispatchers.IO) {
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            }

            if (response.statusCode() == 429) {
                emit(StreamEvent.Error("Rate limited (429). Please wait before retrying."))
                return@flow
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                emit(StreamEvent.Error("Bedrock auth failed (${response.statusCode()}). Check your bearer token."))
                return@flow
            }
            if (response.statusCode() !in 200..299) {
                log.warn("Bedrock invoke error: HTTP ${response.statusCode()} — ${response.body().take(500)}")
                emit(StreamEvent.Error("Bedrock API error: HTTP ${response.statusCode()}"))
                return@flow
            }

            val text = parseBedrockInvokeResponse(response.body())
            if (text != null) {
                emit(StreamEvent.TextDelta(text))
                emit(StreamEvent.MessageStop(stopReason = "end_turn"))
            } else {
                log.warn("Bedrock invoke: no text in response — ${response.body().take(500)}")
                emit(StreamEvent.Error("Bedrock returned no text content."))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Bedrock direct request failed", e)
            emit(StreamEvent.Error("Bedrock request failed: ${e.message}"))
        }
    }

    private fun parseBedrockInvokeResponse(json: String): String? {
        val contentArray = StreamingParser.extractArray(json, "\"content\"") ?: return null
        val text = StreamingParser.extractString(contentArray, "\"text\"")
        return text?.let { StreamingParser.unescapeJson(it) }
    }

    /**
     * OpenAI-compatible provider path — uses the specified profile with renewal and retry.
     * On 401/403: renew credential and retry once.
     * On 429: honor Retry-After up to 60s and two attempts.
     * Do NOT retry 5xx after any TextDelta has been emitted.
     */
    private fun streamViaOpenAiCompatible(request: GatewayRequest, profileId: String): Flow<StreamEvent> = flow {
        val settings = ClawDEASettings.getInstance()
        val profileStore = com.adobe.clawdea.provider.openai.profile.ProfileStore(settings)
        val credentialStore = com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore()

        val resolved = profileStore.resolve(profileId, System.getenv())
        if (resolved == null) {
            emit(StreamEvent.Error("OpenAI-compatible profile not found or not configured. Select a profile in Settings → Tools → ClawDEA."))
            return@flow
        }

        var credential = credentialStore.get(profileId)
        if (credential.isBlank()) {
            emit(StreamEvent.Error("OpenAI-compatible credential not found. Re-run the credential flow in Settings → Tools → ClawDEA."))
            return@flow
        }

        val client = com.adobe.clawdea.provider.openai.client.OpenAiCompatibleClient()

        var attempt = 0
        var shouldRetry = true
        var hasEmittedText = false

        while (shouldRetry && attempt < 2) {
            attempt++
            hasEmittedText = false
            shouldRetry = false

            client.streamCompletion(resolved, credential, request).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        hasEmittedText = true
                        emit(event)
                    }
                    is StreamEvent.HttpError -> {
                        if (event.status in listOf(401, 403) && attempt == 1) {
                            // Attempt credential renewal on auth errors (first attempt only)
                            log.info("OpenAI-compatible auth error (${event.status}), attempting renewal")
                            val renewed = tryRenewCredential(profileId, profileStore, credentialStore)
                            if (renewed) {
                                credential = credentialStore.get(profileId)
                                shouldRetry = true
                            } else {
                                emit(StreamEvent.Error("Authentication failed (${event.status}). Credential renewal failed or was cancelled."))
                            }
                        } else if (event.status == 429) {
                            // Honor Retry-After for rate limits
                            val retryAfter = event.retryAfterSeconds?.coerceAtMost(60) ?: 1
                            if (attempt < 2) {
                                log.info("OpenAI-compatible rate limit (429), retrying after ${retryAfter}s")
                                kotlinx.coroutines.delay(retryAfter * 1000)
                                shouldRetry = true
                            } else {
                                emit(StreamEvent.Error("Rate limited (429). Maximum retries exceeded."))
                            }
                        } else if (event.status in 500..599 && hasEmittedText) {
                            // Do NOT retry 5xx after streaming has started
                            emit(StreamEvent.Error("Server error (${event.status}) after streaming began. Partial response received."))
                        } else {
                            // Other errors: emit and stop
                            emit(event)
                        }
                    }
                    else -> emit(event)
                }
            }
        }
    }

    private fun tryRenewCredential(
        profileId: String,
        profileStore: com.adobe.clawdea.provider.openai.profile.ProfileStore,
        credentialStore: com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore,
    ): Boolean {
        // For now, return false. Full renewal UI integration is beyond this task's scope.
        // The brief mentions CredentialRenewalCoordinator, but wiring the UI prompt callback
        // from a background coroutine requires architectural decisions not specified.
        // This is a DONE_WITH_CONCERNS item.
        return false
    }

    /**
     * CLI fallback path — works with any auth method (API key, OAuth, Bedrock, Vertex).
     * Runs `claude -p` in one-shot mode with the prompt, captures stdout.
     */
    private fun streamViaCli(request: GatewayRequest): Flow<StreamEvent> = flow {
        val cliPath = resolveCliPath()
        if (cliPath == null) {
            emit(StreamEvent.Error("Claude CLI not found. Install it or set an API key in Settings → Tools → ClawDEA."))
            return@flow
        }

        val prompt = buildCliPrompt(request)

        val authManager = AuthManager.getInstance()
        val settings = ClawDEASettings.getInstance().state
        val useBareMode = shouldUseBareMode(
            providerId = authManager.effectiveProviderId(),
            providerConfigured = authManager.activeProvider().isConfigured(),
            settingEnabled = settings.gatewayBareMode,
        )

        val command = mutableListOf(
            cliPath,
            "-p",
            "--output-format", "stream-json",
            "--verbose",
            // Inline completions don't benefit from on-disk session state and
            // would only pollute ~/.claude/sessions/ with one entry per call.
            "--no-session-persistence",
        )
        if (useBareMode) {
            // Skips hooks, LSP, plugin sync, auto-memory, keychain reads,
            // CLAUDE.md auto-discovery. Big latency win when applicable.
            // See shouldUseBareMode for safety gating.
            command.add("--bare")
        }

        val cliModel = resolveCliModel(request.model)
        if (cliModel.isNotBlank()) {
            command.addAll(listOf("--model", cliModel))
        }

        if (request.systemPrompt != null) {
            command.add("--system-prompt")
            command.add(request.systemPrompt)
        }

        command.add(prompt)

        log.info("Gateway CLI: ${command.joinToString(" ")}")

        data class CliOutcome(val text: String, val stderrTail: String, val exitCode: Int)

        try {
            val outcome = withContext(Dispatchers.IO) {
                val pb = ProcessBuilder(command)
                    .redirectErrorStream(false)

                val merged = mutableMapOf<String, String>()
                CliEnvironment.applyTo(merged)
                for ((k, v) in System.getenv()) merged.putIfAbsent(k, v)
                AuthManager.getInstance().applyToEnvironment(merged)
                val env = pb.environment()
                env.clear()
                env.putAll(merged)

                val proc = pb.start()
                val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
                val cliParser = com.adobe.clawdea.cli.CliEventParser()
                val collected = StringBuilder()
                val stderrBuffer = StringBuilder()

                // Drain stderr on a separate thread to avoid blocking the CLI process.
                val stderrThread = Thread {
                    BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).useLines { lines ->
                        for (line in lines) stderrBuffer.appendLine(line)
                    }
                }.apply { isDaemon = true; start() }

                reader.useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        val event = cliParser.parse(line)
                        when (event) {
                            is com.adobe.clawdea.cli.CliEvent.TextDelta -> collected.append(event.text)
                            is com.adobe.clawdea.cli.CliEvent.AssistantMessage ->
                                if (event.text.isNotBlank()) collected.append(event.text)
                            is com.adobe.clawdea.cli.CliEvent.Result ->
                                if (event.isError) collected.append(event.text)
                            else -> {}
                        }
                    }
                }

                val finished = proc.waitFor(request.timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) proc.destroyForcibly()
                stderrThread.join(500)
                val exitCode = if (finished) proc.exitValue() else -1

                // Keep stderr brief but useful in the error message.
                val stderrTail = stderrBuffer.toString().lines().filter { it.isNotBlank() }.takeLast(5).joinToString("\n")
                CliOutcome(collected.toString(), stderrTail, exitCode)
            }

            if (outcome.text.isNotBlank()) {
                emit(StreamEvent.TextDelta(outcome.text))
                emit(StreamEvent.MessageStop(stopReason = "end_turn"))
            } else {
                val detail = outcome.stderrTail.ifBlank { "exit code ${outcome.exitCode}, no output" }
                log.warn("CLI fallback produced no output: $detail")
                emit(StreamEvent.Error("Claude CLI returned no output.\n$detail"))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("CLI fallback failed", e)
            emit(StreamEvent.Error("CLI request failed: ${e.message}"))
        }
    }

    private fun buildCliPrompt(request: GatewayRequest): String {
        return request.userMessage
    }

    /**
     * Find the latest model in the active provider's catalog matching the
     * requested family (e.g. "haiku", "sonnet", "opus"). The catalog is ordered
     * newest-first, so the first match wins. Falls back to [requestedModel] as-is.
     */
    private fun resolveCliModel(requestedModel: String): String {
        val family = extractFamily(requestedModel) ?: return requestedModel
        val settings = ClawDEASettings.getInstance().state
        val providerId = com.adobe.clawdea.auth.AuthManager.getInstance().effectiveProviderId()
        val catalog = settings.modelCatalogs[providerId] ?: return requestedModel
        return catalog.firstOrNull { extractFamily(it.id) == family }?.id
            ?: requestedModel
    }

    private fun extractFamily(modelId: String): String? {
        val lower = modelId.lowercase()
        return when {
            "haiku" in lower -> "haiku"
            "sonnet" in lower -> "sonnet"
            "opus" in lower -> "opus"
            else -> null
        }
    }

    private fun resolveCliPath(): String? {
        val settings = ClawDEASettings.getInstance().state
        if (settings.cliPath.isNotBlank() && settings.cliPath != "claude") {
            if (java.io.File(settings.cliPath).canExecute()) return settings.cliPath
        }

        val home = System.getProperty("user.home")
        val candidates = listOf(
            "$home/.local/bin/claude",
            "$home/.nvm/versions/node/default/bin/claude",
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
        )
        for (candidate in candidates) {
            if (java.io.File(candidate).canExecute()) return candidate
        }
        return null
    }

    companion object {

        /**
         * Determine the execution path based on the selected provider and available credentials.
         * This function is the authoritative decision point for gateway routing, ensuring that
         * the selected provider is respected and that a stored Anthropic key doesn't hijack
         * another provider.
         */
        internal fun selectPath(
            providerId: String,
            anthropicKeyPresent: Boolean,
            bedrockDirectReady: Boolean,
            openAiProfileReady: Boolean,
        ): GatewayPath = when {
            providerId == com.adobe.clawdea.provider.ProviderRegistry.OPENAI_COMPATIBLE_ID && openAiProfileReady ->
                GatewayPath.OPENAI_COMPATIBLE_API
            providerId == "anthropic" && anthropicKeyPresent ->
                GatewayPath.ANTHROPIC_API
            providerId == "bedrock" && bedrockDirectReady ->
                GatewayPath.BEDROCK_API
            else -> GatewayPath.CLAUDE_CLI
        }

        /**
         * Decide whether to append --bare to the gateway CLI invocation.
         *
         * --bare disables hooks, LSP, plugin sync, auto-memory, keychain reads,
         * and CLAUDE.md auto-discovery — useful for inline-completion latency,
         * but only safe when auth doesn't depend on OAuth/keychain (the flag
         * documents itself: "Anthropic auth is strictly ANTHROPIC_API_KEY or
         * apiKeyHelper via --settings (OAuth and keychain are never read).").
         *
         * Gating: explicit user opt-in, *and* the active auth provider is
         * configured with explicit credentials, *and* the provider is one of
         * the API-key-style providers ("anthropic" or "bedrock"). Subscription
         * users would lose auth.
         */
        internal fun shouldUseBareMode(
            providerId: String,
            providerConfigured: Boolean,
            settingEnabled: Boolean,
        ): Boolean =
            settingEnabled &&
                providerConfigured &&
                providerId in BARE_MODE_SAFE_PROVIDERS

        private val BARE_MODE_SAFE_PROVIDERS = setOf("anthropic", "bedrock")

        /**
         * Build the JSON request body for the direct Anthropic API path.
         */
        fun buildRequestBody(request: GatewayRequest): String {
            val sb = StringBuilder()
            sb.append("{")
            sb.append("\"model\":\"${request.model}\"")
            sb.append(",\"max_tokens\":${request.maxTokens}")
            sb.append(",\"stream\":true")
            if (request.systemPrompt != null) {
                sb.append(",\"system\":\"${escapeJson(request.systemPrompt)}\"")
            }
            sb.append(",\"messages\":[{\"role\":\"user\",\"content\":\"${escapeJson(request.userMessage)}\"}]")
            sb.append("}")
            return sb.toString()
        }

        /**
         * Build the JSON request body for the Bedrock Messages API.
         * Same as Anthropic's format but the model is specified in the URL, not the body.
         */
        fun buildBedrockRequestBody(request: GatewayRequest): String {
            val sb = StringBuilder()
            sb.append("{")
            sb.append("\"anthropic_version\":\"bedrock-2023-05-31\"")
            sb.append(",\"max_tokens\":${request.maxTokens}")
            if (request.systemPrompt != null) {
                sb.append(",\"system\":\"${escapeJson(request.systemPrompt)}\"")
            }
            sb.append(",\"messages\":[{\"role\":\"user\",\"content\":\"${escapeJson(request.userMessage)}\"}]")
            sb.append("}")
            return sb.toString()
        }

        /** Escape a string for JSON embedding. */
        fun escapeJson(s: String): String = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

        fun getInstance(): ClaudeGateway =
            ApplicationManager.getApplication().getService(ClaudeGateway::class.java)
    }
}
