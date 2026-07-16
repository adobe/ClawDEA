package com.adobe.clawdea.provider.openai.profile

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileImportExportTest {
    @Test
    fun `export template contains structure without user secrets`() {
        val profile = validatedProfile()
        val exported = ProfileImportExport.exportTemplate(profile)
        assertNoSecrets(exported, "user-password", "temp-token-abc", "env-secret-value", "durable-api-key")
        val parsed = JsonParser.parseString(exported).asJsonObject
        assertTrue(parsed.has("credentialFlow"))
        assertEquals("example.provider", parsed.get("id").asString)
    }

    @Test
    fun `export configured redacts secrets and includes credential ref`() {
        val profile = validatedProfile().copy(
            settings = validatedProfile().settings + ProfileSetting(
                id = "region",
                label = "Region",
            ),
        )
        val values = mapOf(
            "tenant" to "environment-backed-value",
            "region" to "region-1",
            "password" to "user-password",
            "token" to "temp-token-abc",
            "env" to "env-secret-value",
            "apiKey" to "durable-api-key",
        )
        val exported = ProfileImportExport.exportConfigured(profile, values)
        assertNoSecrets(
            exported,
            "environment-backed-value",
            "user-password",
            "temp-token-abc",
            "env-secret-value",
            "durable-api-key",
        )
        val parsed = JsonParser.parseString(exported).asJsonObject
        assertEquals("passwordsafe:openai-compatible/example.provider", parsed.get("credentialRef").asString)
        val configured = parsed.getAsJsonObject("configuredValues")
        assertEquals("region-1", configured.get("region").asString)
        assertFalse(configured.has("tenant"))
        assertFalse(configured.has("password"))
        assertFalse(configured.has("token"))
        assertFalse(configured.has("env"))
        assertFalse(configured.has("apiKey"))
    }

    @Test
    fun `export configured round-trips profile structure`() {
        val profile = validatedProfile()
        val exported = ProfileImportExport.exportConfigured(profile, mapOf("tenant" to "acme"))
        val parsed = JsonParser.parseString(exported).asJsonObject
        assertEquals(profile.id, parsed.getAsJsonObject("profile").get("id").asString)
        assertEquals(profile.baseUrl, parsed.getAsJsonObject("profile").get("baseUrl").asString)
    }

    @Test
    fun `export defensively excludes values whose ids collide with credentials`() {
        val original = validatedProfile()
        val malformedProfile = original.copy(
            settings = listOf(ProfileSetting(id = "password", label = "Password-shaped setting")),
        )
        val exported = ProfileImportExport.exportConfigured(
            malformedProfile,
            mapOf("password" to "must-never-export"),
        )
        assertFalse(exported.contains("must-never-export"))
        assertFalse(
            JsonParser.parseString(exported)
                .asJsonObject
                .getAsJsonObject("configuredValues")
                .has("password"),
        )
    }

    private fun validatedProfile(): OpenAiCompatibleProfile {
        val json = this::class.java.classLoader
            .getResourceAsStream("openai-compatible/minimal-profile.json")!!
            .bufferedReader()
            .readText()
        return (ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Valid).profile
    }

    private fun assertNoSecrets(exported: String, vararg secrets: String) {
        secrets.forEach { secret ->
            assertFalse("Export must not contain '$secret'", exported.contains(secret))
        }
    }
}
