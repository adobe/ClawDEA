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

class SubscriptionAuthProvider(
    private val isSignedIn: () -> Boolean,
) : AuthProvider {
    override val id = "subscription"

    constructor() : this(
        isSignedIn = { SubscriptionAuth.getInstance().getStatus().isSignedIn() },
    )

    constructor(isSignedIn: Boolean) : this(
        isSignedIn = { isSignedIn },
    )

    override fun isConfigured(): Boolean = isSignedIn()
    override fun applyToEnvironment(env: MutableMap<String, String>) {
        env.remove("CLAUDE_CODE_USE_BEDROCK")
        env.remove("CLAUDE_CODE_USE_VERTEX")
        env.remove("AWS_BEARER_TOKEN_BEDROCK")
        env.remove("ANTHROPIC_API_KEY")
    }
    override fun validate(): AuthValidation = if (isConfigured()) {
        AuthValidation(valid = true, message = null)
    } else {
        AuthValidation(valid = false, message = "Not signed in with Claude subscription. Run /login or sign in from Settings > Tools > ClawDEA.")
    }

    override fun testConnection(): ConnectionTestResult {
        if (!isSignedIn()) {
            return ConnectionTestResult(false, "Not signed in. Sign in first, then test the connection.")
        }
        return CliConnectionProbe.probe(this)
    }
}
