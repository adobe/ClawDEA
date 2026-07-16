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
import com.adobe.clawdea.provider.openai.profile.ProfileStore

data class CredentialPromptResult(
    val secretInputs: Map<String, CharArray>,
    val textInputs: Map<String, String>,
)

class CredentialRenewalCoordinator(
    private val profileStore: ProfileStore,
    private val prompt: (OpenAiCompatibleProfile) -> CredentialPromptResult?,
    private val executor: CredentialFlowExecutor,
    private val configuredValues: (String) -> Map<String, String>,
    private val environment: () -> Map<String, String>,
) {
    fun renew(profileId: String): Boolean {
        val profile = profileStore.profile(profileId) ?: return false
        val response = prompt(profile) ?: return false
        return try {
            val result = executor.execute(
                profile = profile,
                secretInputs = response.secretInputs,
                textInputs = response.textInputs,
                configuredValues = configuredValues(profileId),
                environment = environment(),
            )
            result.credential.isNotBlank()
        } catch (e: CredentialFlowException) {
            false
        } finally {
            response.secretInputs.values.forEach { it.fill('\u0000') }
        }
    }
}
