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

import com.adobe.clawdea.provider.openai.profile.CredentialInput
import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile
import com.adobe.clawdea.provider.openai.profile.ProfileSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleSettingsCardModelTest {

    @Test
    fun `dynamic fields prefer environment then persisted then default`() {
        val profile = OpenAiCompatibleProfile(
            id = "test-profile",
            name = "Test Profile",
            description = "A test profile",
            settings = listOf(
                ProfileSetting(id = "team", label = "Team ID", environmentVariable = "TEAM_ID", required = false, defaultValue = "default-team"),
            ),
        )
        val model = OpenAiCompatibleSettingsModel(profile, mapOf("TEAM_ID" to "env-team"))
        val snapshot = model.load(mapOf("team" to "saved-team"))
        assertEquals("env-team", snapshot.configuredValues["team"])
    }

    @Test
    fun `dynamic fields use persisted when environment missing`() {
        val profile = OpenAiCompatibleProfile(
            id = "test-profile",
            name = "Test Profile",
            description = "A test profile",
            settings = listOf(
                ProfileSetting(id = "region", label = "Region", environmentVariable = "REGION", required = false, defaultValue = "us-west"),
            ),
        )
        val model = OpenAiCompatibleSettingsModel(profile, emptyMap())
        val snapshot = model.load(mapOf("region" to "saved-region"))
        assertEquals("saved-region", snapshot.configuredValues["region"])
    }

    @Test
    fun `dynamic fields use default when neither environment nor persisted`() {
        val profile = OpenAiCompatibleProfile(
            id = "test-profile",
            name = "Test Profile",
            description = "A test profile",
            settings = listOf(
                ProfileSetting(id = "api_version", label = "API Version", environmentVariable = null, required = false, defaultValue = "v1"),
            ),
        )
        val model = OpenAiCompatibleSettingsModel(profile, emptyMap())
        val snapshot = model.load(emptyMap())
        assertEquals("v1", snapshot.configuredValues["api_version"])
    }

    @Test
    fun `endpoint override is empty string when not set`() {
        val profile = OpenAiCompatibleProfile(id = "test-profile", name = "Test Profile", description = "A test profile")
        val model = OpenAiCompatibleSettingsModel(profile, emptyMap())
        val snapshot = model.load(emptyMap())
        assertEquals("", snapshot.endpointOverride)
    }

    @Test
    fun `endpoint override is loaded from persisted state`() {
        val profile = OpenAiCompatibleProfile(id = "test-profile", name = "Test Profile", description = "A test profile")
        val model = OpenAiCompatibleSettingsModel(profile, emptyMap())
        model.endpointOverride = "https://custom.example.com"
        val snapshot = model.load(emptyMap())
        assertEquals("https://custom.example.com", snapshot.endpointOverride)
    }

    @Test
    fun `isModified returns false when snapshot matches`() {
        val profile = OpenAiCompatibleProfile(
            id = "test-profile",
            name = "Test Profile",
            description = "A test profile",
            settings = listOf(
                ProfileSetting(id = "key", label = "Key", environmentVariable = null, required = false, defaultValue = "default"),
            ),
        )
        val model = OpenAiCompatibleSettingsModel(profile, emptyMap())
        val snapshot = model.load(mapOf("key" to "default"))
        assertFalse(model.isModified(snapshot, mapOf("key" to "default"), ""))
    }

    @Test
    fun `isModified returns true when configuredValues differ`() {
        val profile = OpenAiCompatibleProfile(
            id = "test-profile",
            name = "Test Profile",
            description = "A test profile",
            settings = listOf(
                ProfileSetting(id = "key", label = "Key", environmentVariable = null, required = false, defaultValue = "default"),
            ),
        )
        val model = OpenAiCompatibleSettingsModel(profile, emptyMap())
        val snapshot = model.load(mapOf("key" to "old-value"))
        assertTrue(model.isModified(snapshot, mapOf("key" to "new-value"), ""))
    }

    @Test
    fun `isModified returns true when endpointOverride differs`() {
        val profile = OpenAiCompatibleProfile(id = "test-profile", name = "Test Profile", description = "A test profile")
        val model = OpenAiCompatibleSettingsModel(profile, emptyMap())
        val snapshot = model.load(emptyMap())
        assertTrue(model.isModified(snapshot, emptyMap(), "https://new-override.com"))
    }

    @Test
    fun `apply updates configuredValues correctly`() {
        val profile = OpenAiCompatibleProfile(
            id = "test-profile",
            name = "Test Profile",
            description = "A test profile",
            settings = listOf(
                ProfileSetting(id = "key1", label = "Key 1", environmentVariable = null, required = false, defaultValue = "default1"),
                ProfileSetting(id = "key2", label = "Key 2", environmentVariable = null, required = false, defaultValue = "default2"),
            ),
        )
        val model = OpenAiCompatibleSettingsModel(profile, emptyMap())
        val newValues = mapOf("key1" to "updated1", "key2" to "updated2")
        val newOverride = "https://updated-endpoint.com"

        val updatedSnapshot = model.apply(newValues, newOverride)

        assertEquals("updated1", updatedSnapshot.configuredValues["key1"])
        assertEquals("updated2", updatedSnapshot.configuredValues["key2"])
        assertEquals("https://updated-endpoint.com", updatedSnapshot.endpointOverride)
    }
}
