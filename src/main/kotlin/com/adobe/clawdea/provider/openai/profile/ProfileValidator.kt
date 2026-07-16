package com.adobe.clawdea.provider.openai.profile

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.net.URI

data class ValidationDiagnostic(val path: String, val message: String)

data class ImportPreview(
    val name: String,
    val description: String,
    val hosts: List<String>,
    val credentialInputs: List<CredentialInput>,
    val environmentVariables: List<String>,
    val settings: List<ProfileSetting>,
)

sealed class ValidationResult {
    data class Valid(val profile: OpenAiCompatibleProfile, val preview: ImportPreview) : ValidationResult()
    data class Invalid(val diagnostics: List<ValidationDiagnostic>) : ValidationResult()
}

object ProfileValidator {
    private val gson = Gson()
    private val PROFILE_ID = Regex("""^[a-z0-9][a-z0-9._-]{2,63}$""")
    private val PLACEHOLDER = Regex("""\$\{(input|setting|env|step):([A-Za-z_][A-Za-z0-9_.-]*)}""")
    private val JSON_PATH = Regex("""^\$(\.[A-Za-z_][A-Za-z0-9_]*)*$""")
    private val LOCALHOST_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1", "0.0.0.0", "[0:0:0:0:0:0:0:1]")
    private val ALLOWED_METHODS = setOf("GET", "POST")

    fun parseAndValidate(json: String, allowLocalHttp: Boolean): ValidationResult {
        val profile = try {
            gson.fromJson(json, OpenAiCompatibleProfile::class.java)
        } catch (e: JsonSyntaxException) {
            return ValidationResult.Invalid(listOf(ValidationDiagnostic("$", "Invalid JSON: ${e.message}")))
        } ?: return ValidationResult.Invalid(listOf(ValidationDiagnostic("$", "Profile must be a JSON object")))

        val diagnostics = mutableListOf<ValidationDiagnostic>()
        validate(profile, allowLocalHttp, diagnostics)
        if (diagnostics.isNotEmpty()) {
            return ValidationResult.Invalid(diagnostics)
        }
        return ValidationResult.Valid(profile, buildPreview(profile))
    }

    private fun validate(profile: OpenAiCompatibleProfile, allowLocalHttp: Boolean, diagnostics: MutableList<ValidationDiagnostic>) {
        if (profile.schemaVersion != 1) {
            diagnostics += ValidationDiagnostic("$.schemaVersion", "Unsupported schema version ${profile.schemaVersion}")
        }
        if (!PROFILE_ID.matches(profile.id)) {
            diagnostics += ValidationDiagnostic(
                "$.id",
                "Profile id must match [a-z0-9][a-z0-9._-]{2,63}",
            )
        }

        val baseUri = parseUri("$.baseUrl", profile.baseUrl, diagnostics) ?: return
        validateScheme(baseUri, allowLocalHttp, diagnostics)

        val settingIds = validateUniqueIds(
            profile.settings.map { it.id },
            "$.settings",
            "Setting ids must be unique",
            diagnostics,
        )
        val inputIds = validateUniqueIds(
            profile.credentialFlow.inputs.map { it.id },
            "$.credentialFlow.inputs",
            "Credential input ids must be unique",
            diagnostics,
        )
        val stepIds = validateUniqueIds(
            profile.credentialFlow.steps.map { it.id },
            "$.credentialFlow.steps",
            "Credential step ids must be unique",
            diagnostics,
        )

        profile.settings.forEachIndexed { index, setting ->
            if (setting.id.isBlank()) {
                diagnostics += ValidationDiagnostic("$.settings[$index].id", "Setting id is required")
            }
        }

        val envVars = profile.settings.mapNotNull { it.environmentVariable?.takeIf { name -> name.isNotBlank() } }.toSet()

        if (resolveEndpoint(baseUri, profile.endpoints.models) == null) {
            diagnostics += ValidationDiagnostic("$.endpoints.models", "Unable to resolve models endpoint")
        } else {
            validateScheme(
                resolveEndpoint(baseUri, profile.endpoints.models)!!,
                allowLocalHttp,
                diagnostics,
                "$.endpoints.models",
            )
        }
        if (resolveEndpoint(baseUri, profile.endpoints.chatCompletions) == null) {
            diagnostics += ValidationDiagnostic("$.endpoints.chatCompletions", "Unable to resolve chat completions endpoint")
        } else {
            validateScheme(
                resolveEndpoint(baseUri, profile.endpoints.chatCompletions)!!,
                allowLocalHttp,
                diagnostics,
                "$.endpoints.chatCompletions",
            )
        }

        validateJsonPath("$.modelMapping.arrayPath", profile.modelMapping.arrayPath, diagnostics)
        validateJsonPath("$.modelMapping.idPath", profile.modelMapping.idPath, diagnostics)
        validateJsonPath("$.modelMapping.displayNamePath", profile.modelMapping.displayNamePath, diagnostics)

        profile.headers.forEach { (key, value) ->
            validatePlaceholders("$.headers.$key", value, settingIds, inputIds, envVars, emptySet(), diagnostics)
        }

        val priorExtractions = mutableSetOf<String>()
        profile.credentialFlow.steps.forEachIndexed { stepIndex, step ->
            val stepPath = "$.credentialFlow.steps[$stepIndex]"
            if (step.id.isBlank()) {
                diagnostics += ValidationDiagnostic("$stepPath.id", "Credential step id is required")
            }
            if (step.method.uppercase() !in ALLOWED_METHODS) {
                diagnostics += ValidationDiagnostic(
                    "$stepPath.method",
                    "Allowed methods are GET and POST",
                )
            }
            val stepUri = resolveEndpoint(baseUri, step.path)
            if (stepUri == null) {
                diagnostics += ValidationDiagnostic("$stepPath.path", "Unable to resolve credential step endpoint")
            } else {
                validateScheme(stepUri, allowLocalHttp, diagnostics, "$stepPath.path")
            }

            step.headers.forEach { (key, value) ->
                validatePlaceholders(
                    "$stepPath.headers.$key",
                    value,
                    settingIds,
                    inputIds,
                    envVars,
                    priorExtractions,
                    diagnostics,
                )
            }
            validatePlaceholders(
                "$stepPath.body",
                step.body,
                settingIds,
                inputIds,
                envVars,
                priorExtractions,
                diagnostics,
            )

            val extractionNames = mutableSetOf<String>()
            step.extracts.forEachIndexed { extractIndex, extraction ->
                val extractPath = "$stepPath.extracts[$extractIndex]"
                if (extraction.name.isBlank()) {
                    diagnostics += ValidationDiagnostic("$extractPath.name", "Extraction name is required")
                } else if (!extractionNames.add(extraction.name)) {
                    diagnostics += ValidationDiagnostic(
                        "$stepPath.extracts",
                        "Extraction names must be unique within a step",
                    )
                }
                validateJsonPath("$extractPath.jsonPath", extraction.jsonPath, diagnostics)
            }
            step.extracts.forEach { priorExtractions.add(it.name) }
        }

        validatePlaceholders(
            "$.credentialFlow.durableCredential",
            profile.credentialFlow.durableCredential,
            settingIds,
            inputIds,
            envVars,
            priorExtractions,
            diagnostics,
        )
    }

    private fun validateUniqueIds(
        ids: List<String>,
        path: String,
        message: String,
        diagnostics: MutableList<ValidationDiagnostic>,
    ): Set<String> {
        val seen = mutableSetOf<String>()
        val duplicates = ids.filter { it.isNotBlank() && !seen.add(it) }.toSet()
        if (duplicates.isNotEmpty()) {
            diagnostics += ValidationDiagnostic(path, message)
        }
        return seen
    }

    private fun parseUri(path: String, value: String, diagnostics: MutableList<ValidationDiagnostic>): URI? {
        if (value.isBlank()) {
            diagnostics += ValidationDiagnostic(path, "URL is required")
            return null
        }
        return try {
            URI(value)
        } catch (e: Exception) {
            diagnostics += ValidationDiagnostic(path, "Invalid URL: ${e.message}")
            null
        }
    }

    private fun resolveEndpoint(baseUri: URI, path: String): URI? =
        try {
            if (path.isBlank()) null else baseUri.resolve(path)
        } catch (_: Exception) {
            null
        }

    private fun isLocalhost(host: String?): Boolean =
        host != null && LOCALHOST_HOSTS.contains(host.lowercase())

    private fun validateScheme(
        uri: URI,
        allowLocalHttp: Boolean,
        diagnostics: MutableList<ValidationDiagnostic>,
        path: String = "$.baseUrl",
    ) {
        val scheme = uri.scheme?.lowercase()
        when {
            scheme == "https" -> Unit
            scheme == "http" && isLocalhost(uri.host) -> {
                if (!allowLocalHttp) {
                    diagnostics += ValidationDiagnostic(
                        path,
                        "Plain HTTP on localhost requires explicit confirmation",
                    )
                }
            }
            scheme == "http" -> {
                diagnostics += ValidationDiagnostic(path, "Remote endpoints must use HTTPS")
            }
            else -> diagnostics += ValidationDiagnostic(path, "Endpoint scheme must be http or https")
        }
    }

    private fun validateJsonPath(path: String, value: String, diagnostics: MutableList<ValidationDiagnostic>) {
        if (value.isBlank()) {
            diagnostics += ValidationDiagnostic(path, "JSON path is required")
            return
        }
        if (!JSON_PATH.matches(value)) {
            diagnostics += ValidationDiagnostic(path, "Invalid JSON path; use $ followed by dot-separated identifiers")
        }
    }

    private fun validatePlaceholders(
        path: String,
        value: String,
        settingIds: Set<String>,
        inputIds: Set<String>,
        envVars: Set<String>,
        priorExtractions: Set<String>,
        diagnostics: MutableList<ValidationDiagnostic>,
    ) {
        var index = 0
        while (index < value.length) {
            val start = value.indexOf('$', index)
            if (start < 0) break
            if (start + 1 >= value.length || value[start + 1] != '{') {
                diagnostics += ValidationDiagnostic(path, "Unsupported placeholder syntax near index $start")
                break
            }
            val end = value.indexOf('}', start + 2)
            if (end < 0) {
                diagnostics += ValidationDiagnostic(path, "Unclosed placeholder near index $start")
                break
            }
            val candidate = value.substring(start, end + 1)
            val match = PLACEHOLDER.matchEntire(candidate)
            if (match == null) {
                diagnostics += ValidationDiagnostic(path, "Unsupported placeholder: $candidate")
            } else {
                val kind = match.groupValues[1]
                val ref = match.groupValues[2]
                when (kind) {
                    "input" -> if (ref !in inputIds) {
                        diagnostics += ValidationDiagnostic(path, "Unknown credential input reference: input:$ref")
                    }
                    "setting" -> if (ref !in settingIds) {
                        diagnostics += ValidationDiagnostic(path, "Unknown setting reference: setting:$ref")
                    }
                    "env" -> if (ref !in envVars) {
                        diagnostics += ValidationDiagnostic(path, "Unknown environment variable reference: env:$ref")
                    }
                    "step" -> if (ref !in priorExtractions) {
                        diagnostics += ValidationDiagnostic(path, "Unknown prior-step extraction reference: step:$ref")
                    }
                }
            }
            index = end + 1
        }
    }

    private fun buildPreview(profile: OpenAiCompatibleProfile): ImportPreview {
        val hosts = linkedSetOf<String>()
        val baseUri = URI(profile.baseUrl)
        baseUri.host?.let { hosts += it }
        profile.credentialFlow.steps.forEach { step ->
            resolveEndpoint(baseUri, step.path)?.host?.let { hosts += it }
        }
        return ImportPreview(
            name = profile.name,
            description = profile.description,
            hosts = hosts.toList(),
            credentialInputs = profile.credentialFlow.inputs,
            environmentVariables = profile.settings.mapNotNull { it.environmentVariable?.takeIf { name -> name.isNotBlank() } },
            settings = profile.settings,
        )
    }
}
