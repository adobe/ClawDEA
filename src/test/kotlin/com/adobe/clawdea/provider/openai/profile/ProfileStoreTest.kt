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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ProfileStoreTest {

    private lateinit var settings: ClawDEASettings
    private lateinit var store: ProfileStore

    @Before
    fun setUp() {
        settings = ClawDEASettings()
        store = ProfileStore(settings)
    }

    @Test
    fun `profiles returns empty list when no profiles imported`() {
        assertEquals(emptyList<OpenAiCompatibleProfile>(), store.profiles())
    }

    @Test
    fun `importValidated persists profile JSON and makes it retrievable`() {
        val profile = testProfile("provider-a", "Provider A")
        store.importValidated(profile)

        val profiles = store.profiles()
        assertEquals(1, profiles.size)
        assertEquals("provider-a", profiles[0].id)
        assertEquals("Provider A", profiles[0].name)
    }

    @Test
    fun `profile returns null when id not found`() {
        assertNull(store.profile("nonexistent"))
    }

    @Test
    fun `profile returns matching profile`() {
        val profile = testProfile("provider-b", "Provider B")
        store.importValidated(profile)

        val retrieved = store.profile("provider-b")
        assertNotNull(retrieved)
        assertEquals("Provider B", retrieved!!.name)
    }

    @Test
    fun `activeProfile returns null when no active profile set`() {
        assertNull(store.activeProfile())
    }

    @Test
    fun `activeProfile returns profile after setting active`() {
        val profile = testProfile("provider-c", "Provider C")
        store.importValidated(profile)
        settings.state.activeOpenAiCompatibleProfileId = "provider-c"

        val active = store.activeProfile()
        assertNotNull(active)
        assertEquals("provider-c", active!!.id)
    }

    @Test
    fun `remove deletes profile and clears active if active`() {
        val profile = testProfile("provider-d", "Provider D")
        store.importValidated(profile)
        settings.state.activeOpenAiCompatibleProfileId = "provider-d"

        store.remove("provider-d")
        assertNull(store.profile("provider-d"))
        assertEquals("", settings.state.activeOpenAiCompatibleProfileId)
    }

    @Test
    fun `resolve returns null when profile not found`() {
        assertNull(store.resolve("nonexistent", emptyMap()))
    }

    @Test
    fun `resolve returns profile with baseUrl from profile when no override`() {
        val profile = testProfile("provider-e", "Provider E", baseUrl = "https://api.example.com")
        store.importValidated(profile)

        val resolved = store.resolve("provider-e", emptyMap())
        assertNotNull(resolved)
        assertEquals("https://api.example.com", resolved!!.baseUrl.toString())
    }

    @Test
    fun `resolve returns profile with overridden baseUrl when override is valid`() {
        val profile = testProfile("provider-f", "Provider F", baseUrl = "https://api.example.com")
        store.importValidated(profile)
        settings.state.openAiEndpointOverrides["provider-f"] = "https://override.example.com"

        val resolved = store.resolve("provider-f", emptyMap())
        assertNotNull(resolved)
        assertEquals("https://override.example.com", resolved!!.baseUrl.toString())
    }

    @Test
    fun `resolve ignores invalid override and uses profile baseUrl`() {
        val profile = testProfile("provider-g", "Provider G", baseUrl = "https://api.example.com")
        store.importValidated(profile)
        settings.state.openAiEndpointOverrides["provider-g"] = "http://remote.example.com"

        val resolved = store.resolve("provider-g", emptyMap())
        assertNotNull(resolved)
        assertEquals("https://api.example.com", resolved!!.baseUrl.toString())
    }

    @Test
    fun `resolve merges environment and persisted values with environment taking precedence`() {
        val profile = testProfile(
            "provider-h",
            "Provider H",
            settings = listOf(
                ProfileSetting("region", "Region", "REGION_ENV", false, "us-east-1"),
                ProfileSetting("tenant", "Tenant", null, false, "default"),
            ),
        )
        store.importValidated(profile)
        settings.state.openAiProfileValues["provider-h|region"] = "us-west-2"
        settings.state.openAiProfileValues["provider-h|tenant"] = "team-a"

        val resolved = store.resolve(
            "provider-h",
            mapOf("REGION_ENV" to "eu-central-1"),
        )
        assertNotNull(resolved)
        assertEquals("eu-central-1", resolved!!.configuredValues["region"])
        assertEquals("team-a", resolved.configuredValues["tenant"])
    }

    @Test
    fun `resolve falls back to profile default when no environment or persisted value`() {
        val profile = testProfile(
            "provider-i",
            "Provider I",
            settings = listOf(
                ProfileSetting("timeout", "Timeout", null, false, "30"),
            ),
        )
        store.importValidated(profile)

        val resolved = store.resolve("provider-i", emptyMap())
        assertNotNull(resolved)
        assertEquals("30", resolved!!.configuredValues["timeout"])
    }

    private fun testProfile(
        id: String,
        name: String,
        baseUrl: String = "https://api.example.com",
        settings: List<ProfileSetting> = emptyList(),
    ) = OpenAiCompatibleProfile(
        schemaVersion = 1,
        id = id,
        name = name,
        description = "Test profile",
        baseUrl = baseUrl,
        settings = settings,
    )
}
