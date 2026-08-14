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

import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URI

/** Shared GET-then-read-JSON helper, collapsing the model/usage probes' duplicated skeletons (Tier 4.5). */
object HttpJson {

    private val log = Logger.getInstance(HttpJson::class.java)

    /**
     * GET [uri] with [headers] (plus `Accept: application/json`), applying [timeoutMs] to both
     * connect and read. Returns the response body on HTTP 200, or null on any non-200 or thrown
     * error (logged with [logTag]).
     */
    fun getJson(uri: String, headers: Map<String, String>, timeoutMs: Int, logTag: String): String? {
        return try {
            val conn = URI(uri).toURL().openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) {
                    log.info("$logTag probe: http ${conn.responseCode}")
                    return null
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            log.info("$logTag probe: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }
}
