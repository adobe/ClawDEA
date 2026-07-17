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
package com.adobe.clawdea.provider.openai.client

import com.adobe.clawdea.gateway.GatewayRequest
import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.gateway.StreamEvent
import com.adobe.clawdea.provider.openai.agent.AgentChatSseParser
import com.adobe.clawdea.provider.openai.agent.AgentCompletionRequest
import com.adobe.clawdea.provider.openai.agent.AgentStreamEvent
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HTTP client for OpenAI-compatible providers.
 * Streams Chat Completions and lists models via the configured profile.
 */
class OpenAiCompatibleClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {
    private val log = Logger.getInstance(OpenAiCompatibleClient::class.java)
    private val parser = OpenAiChatSseParser()
    private val agentParser = AgentChatSseParser()
    private val gson = Gson()

    fun streamCompletion(
        profile: ResolvedProviderProfile,
        credential: String,
        request: GatewayRequest,
    ): Flow<StreamEvent> = flow {
        val chatEndpoint = profile.baseUrl.resolve(profile.profile.endpoints.chatCompletions)
        val body = OpenAiRequestBuilder.build(request)

        val httpRequest = buildHttpRequest(chatEndpoint, credential, profile, body)

        try {
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())

            if (response.statusCode() != 200) {
                emit(handleHttpError(response))
                return@flow
            }

            val lines = response.body()
            for (line in lines) {
                val event = parser.parseLine(line)
                if (event != null) {
                    emit(event)
                }
            }
        } catch (e: Exception) {
            log.info("openai-compatible stream error: ${e.javaClass.simpleName}")
            emit(StreamEvent.Error(e.message ?: "Connection error"))
        }
    }

    fun streamAgentCompletion(
        profile: ResolvedProviderProfile,
        credential: String,
        request: AgentCompletionRequest,
    ): Flow<AgentStreamEvent> = flow {
        val chatEndpoint = profile.baseUrl.resolve(profile.profile.endpoints.chatCompletions)
        val body = buildAgentRequestBody(request)

        // Diagnostic (no content): confirms what we actually send — endpoint, requested stream flag,
        // and model — so a persistent gateway error can be attributed to request vs. server.
        log.info("openai-compatible agent request: url=$chatEndpoint stream=${request.stream} model=${request.model}")

        val httpRequest = buildHttpRequest(chatEndpoint, credential, profile, body)

        try {
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())

            val status = response.statusCode()
            if (status != 200) {
                // Diagnostic (status only, no body): a non-200 here is the top empty-answer suspect.
                log.info("openai-compatible agent stream: http=$status (non-200, aborting)")
                emit(handleAgentHttpError(response))
                return@flow
            }

            // Diagnostic counters (counts only, never response content which carries prompts/output):
            // pairs with AgentLoopController's per-turn summary to localise an empty answer to the
            // wire (0 lines => wrong endpoint/model) vs. parsing/model behaviour.
            //
            // Auto-detect the response shape without profile config: some OpenAI-compatible gateways
            // ignore `stream:true` and return a single non-streamed `chat.completion` JSON object
            // (payload in choices[0].message.*) instead of `data:`-framed SSE (payload in .delta.*).
            // Decide on the first non-blank line: `data:`/`:` framing => SSE (streamed incrementally);
            // otherwise buffer the whole body and parse it as one JSON completion.
            var lineCount = 0
            var parsedCount = 0
            var mode: String? = null
            val jsonBody = StringBuilder()
            for (line in response.body()) {
                lineCount++
                if (mode == null) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) {
                        lineCount-- // ignore leading blanks for the diagnostic count
                        continue
                    }
                    mode = if (trimmed.startsWith("data:") || trimmed.startsWith(":")) "sse" else "json"
                }
                if (mode == "sse") {
                    val event = agentParser.parse(line)
                    if (event != null) {
                        parsedCount++
                        emit(event)
                    }
                } else {
                    jsonBody.append(line).append('\n')
                }
            }
            if (mode == "json") {
                val events = agentParser.parseNonStreamedCompletion(jsonBody.toString())
                parsedCount = events.size
                events.forEach { emit(it) }
            }
            log.info("openai-compatible agent stream: http=$status mode=${mode ?: "empty"} lines=$lineCount parsed=$parsedCount")
        } catch (e: Exception) {
            log.info("openai-compatible agent stream error: ${e.javaClass.simpleName}")
            emit(AgentStreamEvent.Failure(null, e.message ?: "Connection error", null))
        }
    }

    fun listModels(
        profile: ResolvedProviderProfile,
        credential: String,
    ): List<ModelEntry>? {
        val modelsEndpoint = profile.baseUrl.resolve(profile.profile.endpoints.models)
        val httpRequest = buildHttpRequest(modelsEndpoint, credential, profile, null)

        return try {
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != 200) {
                log.info("openai-compatible models probe: HTTP ${response.statusCode()}")
                return null
            }

            parseModels(response.body(), profile)
        } catch (e: Exception) {
            log.info("openai-compatible models probe: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun buildHttpRequest(
        endpoint: URI,
        credential: String,
        profile: ResolvedProviderProfile,
        body: String?,
    ): HttpRequest {
        val builder = HttpRequest.newBuilder()
            .uri(endpoint)
            .header("Authorization", "Bearer $credential")
            .header("Content-Type", "application/json")

        // Merge only validated non-secret profile headers
        profile.profile.headers.forEach { (key, value) ->
            val lowerKey = key.lowercase()
            if (lowerKey != "authorization" && lowerKey != "content-type") {
                builder.header(key, value)
            }
        }

        if (body != null) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.GET()
        }

        return builder.build()
    }

    private fun handleHttpError(response: HttpResponse<*>): StreamEvent.HttpError {
        val status = response.statusCode()
        val message = when (status) {
            401 -> "Authentication failed"
            403 -> "Access forbidden"
            429 -> "Rate limit exceeded"
            in 500..599 -> "Server error"
            else -> "HTTP $status"
        }

        val retryAfter = response.headers().firstValue("Retry-After").orElse(null)?.toLongOrNull()

        return StreamEvent.HttpError(
            status = status,
            message = message,
            retryAfterSeconds = retryAfter,
        )
    }

    private fun handleAgentHttpError(response: HttpResponse<*>): AgentStreamEvent.Failure {
        val status = response.statusCode()
        val message = when (status) {
            401 -> "Authentication failed"
            403 -> "Access forbidden"
            429 -> "Rate limit exceeded"
            in 500..599 -> "Server error"
            else -> "HTTP $status"
        }

        val retryAfter = response.headers().firstValue("Retry-After").orElse(null)?.toLongOrNull()

        return AgentStreamEvent.Failure(
            status = status,
            message = message,
            retryAfterSeconds = retryAfter,
        )
    }

    private fun buildAgentRequestBody(request: AgentCompletionRequest): String {
        val body = JsonObject()
        body.addProperty("model", request.model)
        body.addProperty("max_tokens", request.maxTokens)
        body.addProperty("stream", request.stream)

        // stream_options.include_usage is a STREAMING-only field: some gateways reject it on a
        // non-streamed request. Emit it only when actually streaming.
        if (request.stream) {
            val streamOptions = JsonObject()
            streamOptions.addProperty("include_usage", true)
            body.add("stream_options", streamOptions)
        }

        // Build messages array
        val messagesArray = JsonArray()
        for (message in request.messages) {
            val msgObj = JsonObject()
            msgObj.addProperty("role", message.role)
            if (message.content != null) {
                msgObj.addProperty("content", message.content)
            }
            if (message.toolCalls.isNotEmpty()) {
                val toolCallsArray = JsonArray()
                for (call in message.toolCalls) {
                    val callObj = JsonObject()
                    callObj.addProperty("id", call.id)
                    callObj.addProperty("type", "function")
                    val functionObj = JsonObject()
                    functionObj.addProperty("name", call.name)
                    functionObj.addProperty("arguments", call.argumentsJson)
                    callObj.add("function", functionObj)
                    toolCallsArray.add(callObj)
                }
                msgObj.add("tool_calls", toolCallsArray)
            }
            if (message.toolCallId != null) {
                msgObj.addProperty("tool_call_id", message.toolCallId)
            }
            messagesArray.add(msgObj)
        }
        body.add("messages", messagesArray)

        // Add tools array only if non-empty
        if (request.tools.isNotEmpty()) {
            val toolsArray = JsonArray()
            for (tool in request.tools) {
                val toolObj = JsonObject()
                toolObj.addProperty("type", tool.type)
                val functionObj = JsonObject()
                functionObj.addProperty("name", tool.function.name)
                functionObj.addProperty("description", tool.function.description)
                functionObj.add("parameters", tool.function.parameters)
                toolObj.add("function", functionObj)
                toolsArray.add(toolObj)
            }
            body.add("tools", toolsArray)
        }

        return gson.toJson(body)
    }

    private fun parseModels(json: String, profile: ResolvedProviderProfile): List<ModelEntry>? {
        return try {
            val root = JsonParser.parseString(json)
            val mapping = profile.profile.modelMapping

            // Navigate to the array using arrayPath (e.g., "$.data" -> ["data"])
            var current = root
            val segments = mapping.arrayPath.removePrefix("$.").split(".")
            for (segment in segments) {
                if (segment.isBlank()) continue
                current = current.asJsonObject.get(segment)
                if (current == null || current.isJsonNull) return null
            }

            if (!current.isJsonArray) return null
            val array = current.asJsonArray

            val result = mutableListOf<ModelEntry>()
            for (item in array) {
                if (!item.isJsonObject) continue
                val obj = item.asJsonObject

                // idPath and displayNamePath are simple field names (e.g., "$.id" -> "id")
                val idField = mapping.idPath.removePrefix("$.")
                val displayNameField = mapping.displayNamePath.removePrefix("$.")

                val id = obj.get(idField)?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() } ?: continue

                val displayName = obj.get(displayNameField)?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() } ?: id

                result.add(ModelEntry(id = id, displayName = displayName, userAdded = false))
            }

            result
        } catch (_: Exception) {
            null
        }
    }

}
