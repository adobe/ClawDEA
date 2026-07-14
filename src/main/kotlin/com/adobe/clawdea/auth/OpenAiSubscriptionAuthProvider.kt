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

/**
 * OpenAI ChatGPT subscription provider (id `openai-subscription`), the codex peer of
 * [SubscriptionAuthProvider]. "Configured" means the codex CLI is signed in
 * (`codex login status` -> logged in). [applyToEnvironment] removes `OPENAI_API_KEY` so a stray
 * API key in the environment cannot shadow the ChatGPT sign-in codex picks up from
 * `~/.codex/auth.json`.
 */
class OpenAiSubscriptionAuthProvider(
    private val isSignedIn: () -> Boolean,
) : AuthProvider {
    override val id = "openai-subscription"

    constructor() : this(
        isSignedIn = { CodexSubscriptionAuth.getInstance().getStatus().isSignedIn() },
    )

    constructor(isSignedIn: Boolean) : this(
        isSignedIn = { isSignedIn },
    )

    override fun isConfigured(): Boolean = isSignedIn()

    override fun applyToEnvironment(env: MutableMap<String, String>) {
        env.remove("OPENAI_API_KEY")
    }

    override fun validate(): AuthValidation = if (isConfigured()) {
        AuthValidation(valid = true, message = null)
    } else {
        AuthValidation(
            valid = false,
            message = "Not signed in with your OpenAI (ChatGPT) subscription. Run /login or sign in from Settings > Tools > ClawDEA.",
        )
    }

    override fun testConnection(): ConnectionTestResult = if (isSignedIn()) {
        ConnectionTestResult(true, "Signed in with ChatGPT (codex).")
    } else {
        ConnectionTestResult(false, "Not signed in. Sign in first, then test the connection.")
    }
}
