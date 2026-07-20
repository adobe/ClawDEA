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

import com.adobe.clawdea.provider.AgentSelection

interface AuthProvider {
    val id: String
    fun isConfigured(): Boolean
    fun applyToEnvironment(env: MutableMap<String, String>)
    fun validate(): AuthValidation
    fun testConnection(): ConnectionTestResult

    /**
     * Whether the credentials for a *specific* [selection] are present. Only meaningful for providers
     * whose credentials are keyed by [AgentSelection.profileId] (openai-compatible, which stores one
     * credential per imported profile). The default ignores the selection and defers to the global
     * [isConfigured], which is correct for every single-credential provider (anthropic, bedrock, …).
     */
    fun isConfiguredFor(selection: AgentSelection): Boolean = isConfigured()
}

data class AuthValidation(val valid: Boolean, val message: String?)

data class ConnectionTestResult(
    val success: Boolean,
    val message: String,
    val latencyMs: Long = 0,
)
