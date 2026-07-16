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
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
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
