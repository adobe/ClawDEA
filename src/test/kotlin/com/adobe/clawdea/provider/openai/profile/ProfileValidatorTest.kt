package com.adobe.clawdea.provider.openai.profile

import com.google.gson.JsonNull
import com.google.gson.JsonParser
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
    fun `wildcard address may not use http`() {
        val json = validProfileJson().replace("https://api.example.com", "http://0.0.0.0:8080")
        val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = true) as ValidationResult.Invalid
        assertTrue(invalid.diagnostics.any { it.path == "$.baseUrl" && it.message.contains("HTTPS") })
    }

    @Test
    fun `hostless https URL is rejected`() {
        val json = validProfileJson().replace("https://api.example.com", "https:/api")
        val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
        assertTrue(invalid.diagnostics.any { it.path == "$.baseUrl" && it.message.contains("host") })
    }

    @Test
    fun `preview includes all distinct contacted hosts`() {
        val json = validProfileJson()
            .replace("\"models\": \"/models\"", "\"models\": \"https://models.example.com/models\"")
            .replace(
                "\"chatCompletions\": \"/chat/completions\"",
                "\"chatCompletions\": \"https://chat.example.com/chat/completions\"",
            )
            .replace("\"path\": \"/auth/login\"", "\"path\": \"https://auth.example.com/auth/login\"")
        val valid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Valid
        assertEquals(
            listOf("api.example.com", "models.example.com", "chat.example.com", "auth.example.com"),
            valid.preview.hosts,
        )
    }

    @Test
    fun `null schema fields return diagnostics instead of throwing`() {
        val nullableFields = listOf(
            "settings",
            "credentialFlow",
            "endpoints",
            "headers",
            "modelMapping",
            "modelRules",
            "pricing",
        )
        nullableFields.forEach { field ->
            val profile = JsonParser.parseString(validProfileJson()).asJsonObject
            profile.add(field, JsonNull.INSTANCE)
            val result = ProfileValidator.parseAndValidate(profile.toString(), allowLocalHttp = false)
            assertTrue("$field must fail closed", result is ValidationResult.Invalid)
            val invalid = result as ValidationResult.Invalid
            assertTrue(invalid.diagnostics.any { it.path == "$.$field" })
        }
    }

    @Test
    fun `null nested collections return diagnostics instead of throwing`() {
        val profile = JsonParser.parseString(validProfileJson()).asJsonObject
        profile.getAsJsonObject("credentialFlow").add("inputs", JsonNull.INSTANCE)
        val result = ProfileValidator.parseAndValidate(profile.toString(), allowLocalHttp = false)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).diagnostics.any { it.path == "$.credentialFlow.inputs" })
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
    fun `credential input ids must be nonblank and grammar conforming`() {
        listOf("", "bad input", "9account").forEach { id ->
            val json = validProfileJson().replaceFirst("\"id\": \"account\"", "\"id\": \"$id\"")
            val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
            assertTrue(invalid.diagnostics.any { it.path == "$.credentialFlow.inputs[0].id" })
        }
    }

    @Test
    fun `ids must be disjoint across settings credentials steps and extractions`() {
        val json = validProfileJson().replaceFirst("\"id\": \"tenant\"", "\"id\": \"password\"")
        val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
        assertTrue(invalid.diagnostics.any { it.message.contains("globally unique") })
    }

    @Test
    fun `extraction names are globally unique`() {
        val secondStep = """
            ,{
              "id": "refresh",
              "method": "POST",
              "path": "/auth/refresh",
              "headers": {},
              "body": "",
              "expectedStatuses": [200],
              "extracts": [{"name": "token", "jsonPath": "$.token", "durable": false}]
            }
        """.trimIndent()
        val json = validProfileJson().replace(
            "\n    ],\n    \"durableCredential\"",
            "$secondStep\n    ],\n    \"durableCredential\"",
        )
        val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
        assertTrue(invalid.diagnostics.any { it.message.contains("globally unique") })
    }

    @Test
    fun `numeric schema fields reject JSON strings`() {
        val cases = listOf(
            "\"schemaVersion\": 1" to Pair("\"schemaVersion\": \"1\"", "$.schemaVersion"),
            "\"expectedStatuses\": [200]" to Pair("\"expectedStatuses\": [\"200\"]", "$.credentialFlow.steps[0].expectedStatuses[0]"),
            "\"inputPerM\": 1.0" to Pair("\"inputPerM\": \"1.0\"", "$.pricing.generic-model.inputPerM"),
        )
        cases.forEach { (original, replacementAndPath) ->
            val (replacement, expectedPath) = replacementAndPath
            val invalid = ProfileValidator.parseAndValidate(
                validProfileJson().replace(original, replacement),
                allowLocalHttp = false,
            ) as ValidationResult.Invalid
            assertTrue(expectedPath, invalid.diagnostics.any { it.path == expectedPath })
        }
    }

    @Test
    fun `schema version must be exact supported integer`() {
        listOf("1.5", "1e-1", "2147483648", "2").forEach { version ->
            val json = validProfileJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": $version")
            val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
            assertTrue(
                version,
                invalid.diagnostics.any { it.path == "$.schemaVersion" },
            )
        }
    }

    @Test
    fun `expected statuses must be exact Int values`() {
        listOf("200.5", "2e-1", "2147483648", "-2147483649").forEach { status ->
            val json = validProfileJson().replace(
                "\"expectedStatuses\": [200]",
                "\"expectedStatuses\": [$status]",
            )
            val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
            assertTrue(
                status,
                invalid.diagnostics.any { it.path == "$.credentialFlow.steps[0].expectedStatuses[0]" },
            )
        }
    }

    @Test
    fun `expected statuses must be valid HTTP status codes`() {
        listOf("99", "600").forEach { status ->
            val json = validProfileJson().replace(
                "\"expectedStatuses\": [200]",
                "\"expectedStatuses\": [$status]",
            )
            val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
            assertTrue(
                status,
                invalid.diagnostics.any {
                    it.path == "$.credentialFlow.steps[0].expectedStatuses[0]" &&
                        it.message.contains("100..599")
                },
            )
        }
    }

    @Test
    fun `integral JSON number forms remain valid`() {
        val json = validProfileJson()
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 1.0")
            .replace("\"expectedStatuses\": [200]", "\"expectedStatuses\": [2e2]")
        assertTrue(ProfileValidator.parseAndValidate(json, allowLocalHttp = false) is ValidationResult.Valid)
    }

    @Test
    fun `boolean schema fields reject JSON strings`() {
        val cases = listOf(
            "\"required\": true" to Pair("\"required\": \"true\"", "$.settings[0].required"),
            "\"secret\": false" to Pair("\"secret\": \"false\"", "$.credentialFlow.inputs[0].secret"),
            "\"durable\": true" to Pair("\"durable\": \"true\"", "$.credentialFlow.steps[0].extracts[0].durable"),
        )
        cases.forEach { (original, replacementAndPath) ->
            val (replacement, expectedPath) = replacementAndPath
            val invalid = ProfileValidator.parseAndValidate(
                validProfileJson().replaceFirst(original, replacement),
                allowLocalHttp = false,
            ) as ValidationResult.Invalid
            assertTrue(expectedPath, invalid.diagnostics.any { it.path == expectedPath })
        }
    }

    @Test
    fun `ordinary literal dollar signs are allowed`() {
        val json = validProfileJson().replace(
            "\"Content-Type\": \"application/json\"",
            "\"Content-Type\": \"application/json\", \"X-Price\": \"\$5.00\", \"X-Schema\": \"\$schema\"",
        )
        assertTrue(ProfileValidator.parseAndValidate(json, allowLocalHttp = false) is ValidationResult.Valid)
    }

    @Test
    fun `malformed placeholder constructs are rejected`() {
        listOf("\${input}", "\${input:password", "\${other:password}", "\${input:bad value}").forEach { malformed ->
            val json = validProfileJson().replace(
                "\"Content-Type\": \"application/json\"",
                "\"Content-Type\": \"application/json\", \"X-Test\": \"$malformed\"",
            )
            val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
            assertTrue(invalid.diagnostics.any { it.message.contains("placeholder", ignoreCase = true) })
        }
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
    fun `streaming defaults to true when the field is absent`() {
        val result = ProfileValidator.parseAndValidate(validProfileJson(), allowLocalHttp = false)
        assertTrue(result is ValidationResult.Valid)
        assertTrue((result as ValidationResult.Valid).profile.streaming)
    }

    @Test
    fun `streaming false parses and validates`() {
        val json = validProfileJson().replace(
            "\"schemaVersion\": 1,",
            "\"schemaVersion\": 1,\n  \"streaming\": false,",
        )
        val result = ProfileValidator.parseAndValidate(json, allowLocalHttp = false)
        assertTrue(result is ValidationResult.Valid)
        assertEquals(false, (result as ValidationResult.Valid).profile.streaming)
    }

    @Test
    fun `streaming must be a boolean when present`() {
        val json = validProfileJson().replace(
            "\"schemaVersion\": 1,",
            "\"schemaVersion\": 1,\n  \"streaming\": \"false\",",
        )
        val invalid = ProfileValidator.parseAndValidate(json, allowLocalHttp = false) as ValidationResult.Invalid
        assertTrue(invalid.diagnostics.any { it.path == "$.streaming" })
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
