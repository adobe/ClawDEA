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
package com.adobe.clawdea.debug

enum class AdHocType { JAVA_APP, JAVA_TEST, JS_DEBUG, NODE }
enum class AttachRuntime { JAVA, NODE }

object SessionLauncher {

    fun parseAdHocType(type: String): AdHocType? = when (type.lowercase()) {
        "java_app" -> AdHocType.JAVA_APP
        "java_test" -> AdHocType.JAVA_TEST
        "js_debug" -> AdHocType.JS_DEBUG
        "node" -> AdHocType.NODE
        else -> null
    }

    fun parseRuntime(runtime: String): AttachRuntime? = when (runtime.lowercase()) {
        "java" -> AttachRuntime.JAVA
        "node" -> AttachRuntime.NODE
        else -> null
    }

    fun parseEnvString(env: String): Map<String, String> {
        if (env.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (entry in env.split(",")) {
            val eqIndex = entry.indexOf('=')
            if (eqIndex <= 0) continue
            val key = entry.substring(0, eqIndex).trim()
            val value = entry.substring(eqIndex + 1).trim()
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }

    val isJsSupported: Boolean by lazy {
        try {
            Class.forName("com.intellij.javascript.debugger.JavaScriptDebugProcess")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
