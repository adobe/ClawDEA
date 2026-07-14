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
import java.io.File

/**
 * Populates the OpenAI (ChatGPT-subscription) model catalog from codex's own
 * `models_cache.json`, the exact model list codex uses to build its picker.
 *
 * We deliberately read codex's cache rather than a static list because the
 * account-eligible model set is per-account and per-CLI-version — a ChatGPT
 * account rejects the generic API model IDs (`gpt-5`, `gpt-5-codex`) with HTTP
 * 400, so the only reliable source is what codex itself resolved for this
 * account. `codex` refreshes this cache on startup/login.
 *
 * Only models with `visibility == "list"` are surfaced (codex hides internal
 * entries like `codex-auto-review`). Falls back to null on any IO/parse failure,
 * leaving the persisted catalog untouched.
 */
class CodexModelProbe(
    private val cacheFile: File = defaultCacheFile(),
) : ModelCatalogProbe {

    private val log = Logger.getInstance(CodexModelProbe::class.java)

    override fun probe(): List<ModelEntry>? {
        if (!cacheFile.isFile || cacheFile.length() == 0L) {
            log.info("codex model probe: no cache at ${cacheFile.path}")
            return null
        }
        return try {
            parseModelsCache(cacheFile.readText())
        } catch (t: Throwable) {
            log.info("codex model probe: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    companion object {
        /** `$CODEX_HOME/models_cache.json`, defaulting to `~/.codex/models_cache.json`. */
        internal fun defaultCacheFile(): File {
            val home = System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }
                ?: File(System.getProperty("user.home"), ".codex").path
            return File(home, "models_cache.json")
        }

        /**
         * Parses codex's `models_cache.json`. Returns the listable models (id = slug,
         * displayName = display_name), or null when the payload is missing/malformed.
         * An empty `models` array yields an empty list (a valid "no models" result),
         * not null.
         */
        internal fun parseModelsCache(json: String): List<ModelEntry>? {
            return try {
                val root = JsonParser.parseString(json)
                if (!root.isJsonObject) return null
                val models = root.asJsonObject.get("models")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: return null
                val result = mutableListOf<ModelEntry>()
                for (item in models) {
                    if (!item.isJsonObject) continue
                    val obj = item.asJsonObject
                    val visibility = obj.get("visibility")?.takeIf { it.isJsonPrimitive }?.asString
                    if (visibility != null && visibility != "list") continue
                    val slug = obj.get("slug")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.isNotBlank() } ?: continue
                    val displayName = obj.get("display_name")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.isNotBlank() } ?: slug
                    result.add(ModelEntry(id = slug, displayName = displayName, userAdded = false))
                }
                result
            } catch (_: Exception) {
                null
            }
        }
    }
}
