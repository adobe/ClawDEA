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
package com.adobe.clawdea.provider.openai.profile

import com.adobe.clawdea.settings.ClawDEASettings
import com.google.gson.Gson
import java.net.URI

data class ResolvedProviderProfile(
    val profile: OpenAiCompatibleProfile,
    val baseUrl: URI,
    val configuredValues: Map<String, String>,
)

class ProfileStore(private val settings: ClawDEASettings) {
    private val gson = Gson()

    fun profiles(): List<OpenAiCompatibleProfile> =
        settings.state.importedOpenAiProfiles.values.map { json ->
            gson.fromJson(json, OpenAiCompatibleProfile::class.java)
        }

    fun profile(id: String): OpenAiCompatibleProfile? {
        val json = settings.state.importedOpenAiProfiles[id] ?: return null
        return gson.fromJson(json, OpenAiCompatibleProfile::class.java)
    }

    fun activeProfile(): OpenAiCompatibleProfile? {
        val activeId = settings.state.activeOpenAiCompatibleProfileId
        if (activeId.isBlank()) return null
        return profile(activeId)
    }

    fun importValidated(profile: OpenAiCompatibleProfile) {
        val json = gson.toJson(profile)
        settings.state.importedOpenAiProfiles[profile.id] = json
    }

    fun remove(profileId: String) {
        settings.state.importedOpenAiProfiles.remove(profileId)
        if (settings.state.activeOpenAiCompatibleProfileId == profileId) {
            settings.state.activeOpenAiCompatibleProfileId = ""
        }
        settings.state.openAiProfileValues.keys.removeIf { it.startsWith("$profileId|") }
        settings.state.openAiEndpointOverrides.remove(profileId)
    }

    fun resolve(profileId: String, environment: Map<String, String>): ResolvedProviderProfile? {
        val profile = profile(profileId) ?: return null

        val baseUrl = resolveBaseUrl(profile, profileId)
        val configuredValues = resolveConfiguredValues(profile, profileId, environment)

        return ResolvedProviderProfile(
            profile = profile,
            baseUrl = baseUrl,
            configuredValues = configuredValues,
        )
    }

    private fun resolveBaseUrl(profile: OpenAiCompatibleProfile, profileId: String): URI {
        val override = settings.state.openAiEndpointOverrides[profileId]
        if (override.isNullOrBlank()) {
            return URI(profile.baseUrl)
        }

        val overriddenProfile = profile.copy(baseUrl = override)
        val json = gson.toJson(overriddenProfile)
        val validation = ProfileValidator.parseAndValidate(json, allowLocalHttp = false)

        return when (validation) {
            is ValidationResult.Valid -> URI(override)
            is ValidationResult.Invalid -> URI(profile.baseUrl)
        }
    }

    private fun resolveConfiguredValues(
        profile: OpenAiCompatibleProfile,
        profileId: String,
        environment: Map<String, String>,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        profile.settings.forEach { setting ->
            val envVar = setting.environmentVariable
            val envValue = if (envVar != null && envVar.isNotBlank()) environment[envVar] else null
            val persistedValue = settings.state.openAiProfileValues["$profileId|${setting.id}"]
            val value = envValue ?: persistedValue ?: setting.defaultValue
            result[setting.id] = value
        }
        return result
    }
}
