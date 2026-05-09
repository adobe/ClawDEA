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

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URI

/**
 * Probes Bedrock's ListInferenceProfiles endpoint via the bearer-token HTTP API.
 * Inference profile ids (with a "us." / "global." prefix) are the actual invokable
 * identifiers for Claude 4.x models — the raw foundation-model ids from
 * ListFoundationModels cannot be invoked directly for those models.
 *
 * Uses AWS_BEARER_TOKEN_BEDROCK from the environment (the same auth Claude Code CLI
 * uses for Bedrock), so we don't need SigV4 credentials or the AWS SDK.
 */
class BedrockModelProbe(
    private val region: String,
    private val bearerToken: String,
    private val timeoutMs: Int = 5000,
) : ModelCatalogProbe {

    private val log = Logger.getInstance(BedrockModelProbe::class.java)

    override fun probe(): List<ModelEntry>? {
        if (region.isBlank() || bearerToken.isBlank()) return null
        return try {
            val url = URI("https://bedrock.$region.amazonaws.com/inference-profiles").toURL()
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Authorization", "Bearer $bearerToken")
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) {
                    log.info("bedrock probe: http ${conn.responseCode}")
                    return null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseModelsJson(body)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            log.info("bedrock probe: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    companion object {
        internal fun parseModelsJson(json: String): List<ModelEntry>? {
            return try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonObject) return null
                val data = root.asJsonObject.get("inferenceProfileSummaries") ?: return null
                if (!data.isJsonArray) return null
                val result = mutableListOf<ModelEntry>()
                for (item in data.asJsonArray) {
                    if (!item.isJsonObject) continue
                    val obj = item.asJsonObject
                    val status = obj.get("status")?.takeIf { it.isJsonPrimitive }?.asString
                    if (status != null && status != "ACTIVE") continue
                    val id = obj.get("inferenceProfileId")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    if (!id.contains(".anthropic.")) continue
                    val name = obj.get("inferenceProfileName")?.takeIf { it.isJsonPrimitive }?.asString ?: id
                    result.add(ModelEntry(id = id, displayName = name, userAdded = false))
                }
                result
            } catch (_: Exception) {
                null
            }
        }
    }
}
