package com.adobe.clawdea.provider.openai.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidatorTest {
    @Test
    fun `valid generic profile produces an import preview`() {
        val result = ProfileValidator.parseAndValidate(validProfileJson(), allowLocalHttp = false)
        assertTrue(result is ValidationResult.Valid)
        val preview = (result as ValidationResult.Valid).preview
        assertEquals(listOf("api.example.com"), preview.hosts)
        assertEquals(listOf("account", "password"), preview.credentialInputs.map { it.id })
    }

    @Test
    fun `remote http endpoint is rejected`() {
        val json = validProfileJson().replace("https://api.example.com", "http://api.example.com")
        val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = true) as ValidationResult.Invalid
        assertTrue(invalid.diagnostics.any { it.path == "$.baseUrl" && it.message.contains("HTTPS") })
    }

    @Test
    fun `localhost http requires explicit confirmation`() {
        val json = validProfileJson().replace("https://api.example.com", "http://127.0.0.1:8080")
        assertTrue(ProfileValidator.parseAndValidate(json, allowLocalHttp = false) is ValidationResult.Invalid)
        assertTrue(ProfileValidator.parseAndValidate(json, allowLocalHttp = true) is ValidationResult.Valid)
    }

    @Test
    fun `duplicate credential input ids are rejected`() {
        val json = validProfileJson().replace(
            """"id": "password"""",
            """"id": "account"""",
        )
        val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
        assertTrue(invalid.diagnostics.any { it.message.contains("unique") })
    }

    @Test
    fun `unknown placeholder reference is rejected`() {
        val json = validProfileJson().replace(
            "\${input:password}",
            "\${input:missing}",
        )
        val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
        assertTrue(invalid.diagnostics.any { it.message.contains("input:missing") })
    }

    @Test
    fun `invalid json path is rejected`() {
        val json = validProfileJson().replace("""$.token""", """$.token[0]""")
        val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
        assertTrue(invalid.diagnostics.any { it.message.contains("JSON path") })
    }

    @Test
    fun `unsupported schema version is rejected`() {
        val json = validProfileJson().replace(""""schemaVersion": 1""", """"schemaVersion": 99""")
        val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
        assertTrue(invalid.diagnostics.any { it.path == "$.schemaVersion" })
    }

    @Test
    fun `invalid profile id is rejected`() {
        val json = validProfileJson().replace(""""id": "example.provider"""", """"id": "INVALID"""")
        val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
        assertTrue(invalid.diagnostics.any { it.path == "$.id" })
    }

    @Test
    fun `forward step reference is rejected`() {
        val json = validProfileJson().replace(
            "\"durableCredential\": \"\${step:token}\"",
            "\"durableCredential\": \"\${step:future}\"",
        )
        val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
        assertTrue(invalid.diagnostics.any { it.message.contains("step:future") })
    }

    @Test
    fun `minimal profile resource validates`() {
        val json = this::class.java.classLoader
            .getResourceAsStream("openai-compatible/minimal-profile.json")!!
            .bufferedReader()
            .readText()
        assertTrue(ProfileValidator.parseAndValidate(json, allowLocalHttp = false) is ValidationResult.Valid)
    }

    private fun validProfileJson(): String =
        this::class.java.classLoader
            .getResourceAsStream("openai-compatible/minimal-profile.json")!!
            .bufferedReader()
            .readText()
}
