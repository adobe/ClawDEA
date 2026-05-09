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

class AnthropicModelProbe(
    private val apiKey: String,
    private val timeoutMs: Int = 5000,
) : ModelCatalogProbe {

    private val log = Logger.getInstance(AnthropicModelProbe::class.java)

    /** See [ModelCatalogProbe.probe]. Must be called off-EDT. */
    override fun probe(): List<ModelEntry>? {
        if (apiKey.isBlank()) return null
        return try {
            val conn = URI("https://api.anthropic.com/v1/models").toURL().openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("x-api-key", apiKey)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) {
                    log.info("anthropic probe: http ${conn.responseCode}")
                    return null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseModelsJson(body)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            log.info("anthropic probe: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    companion object {
        internal fun parseModelsJson(json: String): List<ModelEntry>? {
            return try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonObject) return null
                val data = root.asJsonObject.get("data") ?: return null
                if (!data.isJsonArray) return null
                val result = mutableListOf<ModelEntry>()
                for (item in data.asJsonArray) {
                    if (!item.isJsonObject) continue
                    val obj = item.asJsonObject
                    val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    val displayName = obj.get("display_name")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    result.add(ModelEntry(id = id, displayName = displayName, userAdded = false))
                }
                result
            } catch (_: Exception) {
                null
            }
        }
    }
}
