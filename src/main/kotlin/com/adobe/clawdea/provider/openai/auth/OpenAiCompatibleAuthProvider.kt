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

import com.adobe.clawdea.auth.AuthProvider
import com.adobe.clawdea.auth.AuthValidation
import com.adobe.clawdea.auth.ConnectionTestResult
import com.adobe.clawdea.provider.ProviderRegistry
import com.adobe.clawdea.provider.openai.profile.ProfileStore
import com.adobe.clawdea.settings.ClawDEASettings

class OpenAiCompatibleAuthProvider(
    private val profileStore: () -> ProfileStore,
    private val credentialStore: () -> ProfileCredentialStore,
) : AuthProvider {

    override val id = ProviderRegistry.OPENAI_COMPATIBLE_ID

    constructor() : this(
        profileStore = { ProfileStore(ClawDEASettings.getInstance()) },
        credentialStore = { ProfileCredentialStore() },
    )

    override fun isConfigured(): Boolean {
        val profile = profileStore().activeProfile() ?: return false
        val credential = credentialStore().get(profile.id)
        return credential.isNotBlank()
    }

    override fun applyToEnvironment(env: MutableMap<String, String>) {
        // HTTP client receives credentials directly; no environment variable needed.
    }

    override fun validate(): AuthValidation {
        val profile = profileStore().activeProfile()
        if (profile == null) {
            return AuthValidation(
                valid = false,
                message = "No OpenAI-compatible profile selected. Import a profile in Settings > Tools > ClawDEA.",
            )
        }
        val credential = credentialStore().get(profile.id)
        if (credential.isBlank()) {
            return AuthValidation(
                valid = false,
                message = "Profile '${profile.name}' is selected but has no credential. Run the credential flow in Settings.",
            )
        }
        return AuthValidation(valid = true, message = null)
    }

    override fun testConnection(): ConnectionTestResult {
        return ConnectionTestResult(
            success = false,
            message = "Connection test not implemented for generic profiles (Task 4 will add model-list validation).",
        )
    }
}
