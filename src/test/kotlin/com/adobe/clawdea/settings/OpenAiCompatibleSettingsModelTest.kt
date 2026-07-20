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
import com.adobe.clawdea.provider.openai.profile.ProfileSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiCompatibleSettingsModelTest {

    @Test
    fun `mergeLiveValues - field value wins over resolved when present`() {
        val profile = OpenAiCompatibleProfile(
            id = "test-profile",
            name = "Test Profile",
            settings = listOf(
                ProfileSetting(
                    id = "setting1",
                    label = "Setting 1",
                    defaultValue = "default1",
                ),
                ProfileSetting(
                    id = "setting2",
                    label = "Setting 2",
                    defaultValue = "default2",
                ),
            ),
        )

        val liveFieldValues = mapOf("setting1" to "live1")
        val resolvedValues = mapOf(
            "setting1" to "resolved1",
            "setting2" to "resolved2",
        )

        val merged = OpenAiCompatibleSettingsModel.mergeLiveValues(
            profile = profile,
            liveFieldValues = liveFieldValues,
            resolvedValues = resolvedValues,
        )

        assertEquals("live1", merged["setting1"]) // field wins
        assertEquals("resolved2", merged["setting2"]) // no field, falls back to resolved
    }

    @Test
    fun `mergeLiveValues - resolved value used when field absent`() {
        val profile = OpenAiCompatibleProfile(
            id = "test-profile",
            name = "Test Profile",
            settings = listOf(
                ProfileSetting(
                    id = "setting1",
                    label = "Setting 1",
                    defaultValue = "default1",
                ),
            ),
        )

        val liveFieldValues = emptyMap<String, String>()
        val resolvedValues = mapOf("setting1" to "resolved1")

        val merged = OpenAiCompatibleSettingsModel.mergeLiveValues(
            profile = profile,
            liveFieldValues = liveFieldValues,
            resolvedValues = resolvedValues,
        )

        assertEquals("resolved1", merged["setting1"])
    }

    @Test
    fun `mergeLiveValues - default used when field and resolved both absent`() {
        val profile = OpenAiCompatibleProfile(
            id = "test-profile",
            name = "Test Profile",
            settings = listOf(
                ProfileSetting(
                    id = "setting1",
                    label = "Setting 1",
                    defaultValue = "default1",
                ),
            ),
        )

        val liveFieldValues = emptyMap<String, String>()
        val resolvedValues = emptyMap<String, String>()

        val merged = OpenAiCompatibleSettingsModel.mergeLiveValues(
            profile = profile,
            liveFieldValues = liveFieldValues,
            resolvedValues = resolvedValues,
        )

        assertEquals("default1", merged["setting1"])
    }

    @Test
    fun `mergeLiveValues - field value reflects env-loaded value correctly`() {
        // The card populates fields via load() which applies env→persisted→default.
        // The field thus already reflects the env var if set. This test documents
        // that mergeLiveValues makes the field authoritative, preserving the env
        // precedence already baked into the field.
        val profile = OpenAiCompatibleProfile(
            id = "test-profile",
            name = "Test Profile",
            settings = listOf(
                ProfileSetting(
                    id = "setting1",
                    label = "Setting 1",
                    defaultValue = "default1",
                    environmentVariable = "SETTING1_VAR",
                ),
            ),
        )

        // Simulate: the field was populated from env ("envValue"), but resolved still
        // has the persisted value. The field value should win (it already reflects env).
        val liveFieldValues = mapOf("setting1" to "envValue") // field shows env value
        val resolvedValues = mapOf("setting1" to "persistedValue")

        val merged = OpenAiCompatibleSettingsModel.mergeLiveValues(
            profile = profile,
            liveFieldValues = liveFieldValues,
            resolvedValues = resolvedValues,
        )

        assertEquals("envValue", merged["setting1"]) // field (with env) wins
    }
}
