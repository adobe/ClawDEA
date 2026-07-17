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

import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile
import com.adobe.clawdea.provider.openai.profile.ProfileStore
import com.adobe.clawdea.settings.ClawDEASettings
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleAuthProviderTest {

    private val gson = Gson()

    private fun settingsWithProfile(profile: OpenAiCompatibleProfile?): ClawDEASettings {
        val settings = ClawDEASettings()
        if (profile != null) {
            settings.state.importedOpenAiProfiles[profile.id] = gson.toJson(profile)
            settings.state.activeOpenAiCompatibleProfileId = profile.id
        }
        return settings
    }

    private fun credStore(vararg entries: Pair<String, String>): ProfileCredentialStore {
        val storage = mutableMapOf<String, String?>()
        entries.forEach { (profileId, cred) ->
            storage[com.intellij.credentialStore.generateServiceName(
                "ClawDEA",
                "openai-compatible/profile/$profileId/credential",
            )] = cred
        }
        return ProfileCredentialStore(
            read = { storage[it.serviceName] },
            write = { attr, value -> storage[attr.serviceName] = value },
        )
    }

    @Test
    fun `testConnection reports no profile when none selected`() {
        val settings = settingsWithProfile(null)
        val provider = OpenAiCompatibleAuthProvider(
            profileStore = { ProfileStore(settings) },
            credentialStore = { credStore() },
            listModels = { _, _ -> error("should not probe") },
        )

        val result = provider.testConnection()
        assertFalse(result.success)
        assertEquals("No OpenAI-compatible profile selected.", result.message)
    }

    @Test
    fun `testConnection reports missing credential`() {
        val profile = OpenAiCompatibleProfile(id = "p1", name = "My Profile", baseUrl = "https://example.test")
        val settings = settingsWithProfile(profile)
        val provider = OpenAiCompatibleAuthProvider(
            profileStore = { ProfileStore(settings) },
            credentialStore = { credStore() }, // no credential stored
            listModels = { _, _ -> error("should not probe") },
        )

        val result = provider.testConnection()
        assertFalse(result.success)
        assertTrue(result.message.contains("My Profile"))
        assertTrue(result.message.contains("no credential"))
    }

    @Test
    fun `testConnection succeeds when models are returned`() {
        val profile = OpenAiCompatibleProfile(id = "p2", name = "My Profile", baseUrl = "https://example.test")
        val settings = settingsWithProfile(profile)
        val provider = OpenAiCompatibleAuthProvider(
            profileStore = { ProfileStore(settings) },
            credentialStore = { credStore("p2" to "secret") },
            listModels = { _, _ ->
                listOf(
                    ModelEntry(id = "m1", displayName = "M1"),
                    ModelEntry(id = "m2", displayName = "M2"),
                )
            },
        )

        val result = provider.testConnection()
        assertTrue(result.success)
        assertTrue(result.message.contains("2 models"))
    }

    @Test
    fun `testConnection fails when probe returns null`() {
        val profile = OpenAiCompatibleProfile(id = "p3", name = "My Profile", baseUrl = "https://example.test")
        val settings = settingsWithProfile(profile)
        val provider = OpenAiCompatibleAuthProvider(
            profileStore = { ProfileStore(settings) },
            credentialStore = { credStore("p3" to "secret") },
            listModels = { _, _ -> null },
        )

        val result = provider.testConnection()
        assertFalse(result.success)
        assertTrue(result.message.contains("check the endpoint"))
    }
}
