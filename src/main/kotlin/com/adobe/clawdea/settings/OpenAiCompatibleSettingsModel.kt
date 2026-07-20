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
package com.adobe.clawdea.settings

import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile

data class OpenAiCompatibleSettingsSnapshot(
    val activeProfileId: String,
    val configuredValues: Map<String, String>,
    val endpointOverride: String,
)

class OpenAiCompatibleSettingsModel(
    private val profile: OpenAiCompatibleProfile,
    private val environment: Map<String, String>,
) {
    var endpointOverride: String = ""

    fun load(persistedValues: Map<String, String>): OpenAiCompatibleSettingsSnapshot {
        val configuredValues = mutableMapOf<String, String>()
        profile.settings.forEach { setting ->
            val envVar = setting.environmentVariable
            val envValue = if (envVar != null && envVar.isNotBlank()) environment[envVar] else null
            val persistedValue = persistedValues[setting.id]
            val value = envValue ?: persistedValue ?: setting.defaultValue
            configuredValues[setting.id] = value
        }
        return OpenAiCompatibleSettingsSnapshot(
            activeProfileId = profile.id,
            configuredValues = configuredValues,
            endpointOverride = endpointOverride,
        )
    }

    fun isModified(
        snapshot: OpenAiCompatibleSettingsSnapshot,
        currentValues: Map<String, String>,
        currentOverride: String,
    ): Boolean {
        if (snapshot.endpointOverride != currentOverride) return true
        return snapshot.configuredValues != currentValues
    }

    fun apply(
        newValues: Map<String, String>,
        newOverride: String,
    ): OpenAiCompatibleSettingsSnapshot {
        endpointOverride = newOverride
        return OpenAiCompatibleSettingsSnapshot(
            activeProfileId = profile.id,
            configuredValues = newValues,
            endpointOverride = newOverride,
        )
    }

    companion object {
        /**
         * Merges live UI field values over persisted/env/default resolution.
         * Precedence: live field (if present) > resolved value from ProfileStore.
         * The card already populates fields from env→persisted→default, so the field
         * reflects the effective value; this function makes the field value authoritative
         * for actions (Connect, Refresh Models, Verify Tool Support).
         */
        fun mergeLiveValues(
            profile: OpenAiCompatibleProfile,
            liveFieldValues: Map<String, String>,
            resolvedValues: Map<String, String>,
        ): Map<String, String> {
            val merged = mutableMapOf<String, String>()
            profile.settings.forEach { setting ->
                merged[setting.id] = liveFieldValues[setting.id] ?: resolvedValues[setting.id] ?: setting.defaultValue
            }
            return merged
        }
    }
}
