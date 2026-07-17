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
package com.adobe.clawdea.provider.openai.auth

import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile
import com.google.gson.JsonParser
import java.net.URI

data class ProfileHttpRequest(
    val method: String,
    val uri: URI,
    val headers: Map<String, String>,
    val body: String,
)

data class ProfileHttpResponse(val status: Int, val body: String)

fun interface ProfileHttpTransport {
    fun execute(request: ProfileHttpRequest): ProfileHttpResponse
}

data class CredentialFlowResult(
    val credential: String,
    val retainedValues: Map<String, String>,
)

class CredentialFlowException(message: String, cause: Throwable? = null) : Exception(message, cause)

class CredentialFlowExecutor(
    private val transport: ProfileHttpTransport,
    private val credentialStore: ProfileCredentialStore,
) {
    private val placeholderRegex = Regex("""\$\{(input|setting|env|step):([A-Za-z_][A-Za-z0-9_.-]*)}""")

    fun execute(
        profile: OpenAiCompatibleProfile,
        secretInputs: Map<String, CharArray>,
        textInputs: Map<String, String>,
        configuredValues: Map<String, String>,
        environment: Map<String, String>,
    ): CredentialFlowResult {
        try {
            val extractions = mutableMapOf<String, String>()

            profile.credentialFlow.steps.forEach { step ->
                val uri = URI(profile.baseUrl).resolve(step.path)
                val headers = step.headers.mapValues { (_, value) ->
                    expand(value, secretInputs, textInputs, configuredValues, environment, extractions)
                }
                val body = expand(step.body, secretInputs, textInputs, configuredValues, environment, extractions)

                val request = ProfileHttpRequest(
                    method = step.method,
                    uri = uri,
                    headers = headers,
                    body = body,
                )

                val response = transport.execute(request)
                if (response.status !in step.expectedStatuses) {
                    // Include a bounded snippet of the server's response body — it carries the real
                    // reason (e.g. FastAPI/LiteLLM `{"detail": ...}`) and is the only way the user
                    // can diagnose a rejected step. This is the server's RESPONSE, never the request,
                    // so no submitted secret is exposed here.
                    val detail = response.body.trim().take(500)
                    val suffix = if (detail.isEmpty()) "" else ": $detail"
                    throw CredentialFlowException(
                        "Step ${step.id} failed: expected ${step.expectedStatuses}, got ${response.status}$suffix",
                    )
                }

                step.extracts.forEach { extraction ->
                    val value = extractJsonValue(response.body, extraction.jsonPath)
                    extractions[extraction.name] = value
                }
            }

            val durableCredential = expand(
                profile.credentialFlow.durableCredential,
                secretInputs,
                textInputs,
                configuredValues,
                environment,
                extractions,
            )

            credentialStore.set(profile.id, durableCredential)

            val retainedValues = profile.credentialFlow.steps
                .flatMap { it.extracts }
                .filter { it.durable }
                .associate { it.name to extractions.getValue(it.name) }

            return CredentialFlowResult(
                credential = durableCredential,
                retainedValues = retainedValues,
            )
        } finally {
            secretInputs.values.forEach { it.fill('\u0000') }
        }
    }

    private fun expand(
        template: String,
        secretInputs: Map<String, CharArray>,
        textInputs: Map<String, String>,
        configuredValues: Map<String, String>,
        environment: Map<String, String>,
        extractions: Map<String, String>,
    ): String {
        return placeholderRegex.replace(template) { match ->
            val kind = match.groupValues[1]
            val ref = match.groupValues[2]
            when (kind) {
                "input" -> {
                    val secretValue = secretInputs[ref]
                    if (secretValue != null) {
                        String(secretValue)
                    } else {
                        textInputs[ref] ?: throw CredentialFlowException("Unknown input: $ref")
                    }
                }
                "setting" -> configuredValues[ref] ?: throw CredentialFlowException("Unknown setting: $ref")
                "env" -> environment[ref] ?: throw CredentialFlowException("Unknown environment variable: $ref")
                "step" -> extractions[ref] ?: throw CredentialFlowException("Unknown step extraction: $ref")
                else -> throw CredentialFlowException("Unknown placeholder kind: $kind")
            }
        }
    }

    private fun extractJsonValue(json: String, path: String): String {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw CredentialFlowException("Invalid JSON response: ${e.message}", e)
        }

        val parts = path.removePrefix("$.").split(".")
        var current = root
        parts.forEach { part ->
            if (!current.isJsonObject) {
                throw CredentialFlowException("Path $path: not an object at $part")
            }
            val next = current.asJsonObject.get(part)
                ?: throw CredentialFlowException("Path $path: missing field $part")
            current = next
        }

        if (!current.isJsonPrimitive) {
            throw CredentialFlowException("Path $path: value is not a scalar")
        }

        return current.asString
    }
}
