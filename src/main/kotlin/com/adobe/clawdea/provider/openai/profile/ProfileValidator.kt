package com.adobe.clawdea.provider.openai.profile

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
    private val DECLARED_ID = Regex("""^[A-Za-z_][A-Za-z0-9_.-]*$""")
    private val PLACEHOLDER = Regex("""\$\{(input|setting|env|step):([A-Za-z_][A-Za-z0-9_.-]*)}""")
    private val JSON_PATH = Regex("""^\$(\.[A-Za-z_][A-Za-z0-9_]*)*$""")
    private val LOCALHOST_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    private val ALLOWED_METHODS = setOf("GET", "POST")

    fun parseAndValidate(json: String, allowLocalHttp: Boolean): ValidationResult {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            return ValidationResult.Invalid(listOf(ValidationDiagnostic("$", "Invalid JSON: ${e.message}")))
        }
        if (!root.isJsonObject) {
            return ValidationResult.Invalid(listOf(ValidationDiagnostic("$", "Profile must be a JSON object")))
        }

        val diagnostics = mutableListOf<ValidationDiagnostic>()
        validateStructure(root.asJsonObject, diagnostics)
        if (diagnostics.isNotEmpty()) {
            return ValidationResult.Invalid(diagnostics)
        }
        return try {
            val profile = gson.fromJson(root, OpenAiCompatibleProfile::class.java)
            validate(profile, allowLocalHttp, diagnostics)
            if (diagnostics.isNotEmpty()) {
                ValidationResult.Invalid(diagnostics)
            } else {
                ValidationResult.Valid(profile, buildPreview(profile))
            }
        } catch (e: Exception) {
            ValidationResult.Invalid(
                diagnostics.ifEmpty {
                    listOf(ValidationDiagnostic("$", "Profile validation failed: ${e.message}"))
                },
            )
        }
    }

    private fun validateStructure(root: JsonObject, diagnostics: MutableList<ValidationDiagnostic>) {
        requireNumber(root, "schemaVersion", "$.schemaVersion", diagnostics)
        listOf("id", "name", "description", "baseUrl").forEach { field ->
            requireString(root, field, "$.$field", diagnostics)
        }
        val endpoints = requireObject(root, "endpoints", "$.endpoints", diagnostics)
        endpoints?.let {
            requireString(it, "models", "$.endpoints.models", diagnostics)
            requireString(it, "chatCompletions", "$.endpoints.chatCompletions", diagnostics)
        }
        requireObject(root, "headers", "$.headers", diagnostics)
            ?.let { validateStringMap(it, "$.headers", diagnostics) }
        requireArray(root, "settings", "$.settings", diagnostics)?.forEachIndexed { index, value ->
            val path = "$.settings[$index]"
            val setting = requireElementObject(value, path, diagnostics) ?: return@forEachIndexed
            requireString(setting, "id", "$path.id", diagnostics)
            requireString(setting, "label", "$path.label", diagnostics)
            validateNullableString(setting, "environmentVariable", "$path.environmentVariable", diagnostics)
            requireBoolean(setting, "required", "$path.required", diagnostics)
            requireString(setting, "defaultValue", "$path.defaultValue", diagnostics)
        }
        val credentialFlow = requireObject(root, "credentialFlow", "$.credentialFlow", diagnostics)
        credentialFlow?.let {
            requireArray(it, "inputs", "$.credentialFlow.inputs", diagnostics)?.forEachIndexed { index, value ->
                val path = "$.credentialFlow.inputs[$index]"
                val input = requireElementObject(value, path, diagnostics) ?: return@forEachIndexed
                requireString(input, "id", "$path.id", diagnostics)
                requireString(input, "label", "$path.label", diagnostics)
                requireBoolean(input, "secret", "$path.secret", diagnostics)
            }
            requireArray(it, "steps", "$.credentialFlow.steps", diagnostics)?.forEachIndexed { index, value ->
                validateStepStructure(value, index, diagnostics)
            }
            requireString(it, "durableCredential", "$.credentialFlow.durableCredential", diagnostics)
        }
        val modelMapping = requireObject(root, "modelMapping", "$.modelMapping", diagnostics)
        modelMapping?.let {
            requireString(it, "arrayPath", "$.modelMapping.arrayPath", diagnostics)
            requireString(it, "idPath", "$.modelMapping.idPath", diagnostics)
            requireString(it, "displayNamePath", "$.modelMapping.displayNamePath", diagnostics)
        }
        requireArray(root, "modelRules", "$.modelRules", diagnostics)?.forEachIndexed { index, value ->
            val path = "$.modelRules[$index]"
            val rule = requireElementObject(value, path, diagnostics) ?: return@forEachIndexed
            requireString(rule, "pattern", "$path.pattern", diagnostics)
            requireString(rule, "capability", "$path.capability", diagnostics)
        }
        requireObject(root, "pricing", "$.pricing", diagnostics)?.entrySet()?.forEach { (model, value) ->
            val path = "$.pricing.$model"
            val rates = requireElementObject(value, path, diagnostics) ?: return@forEach
            listOf("inputPerM", "outputPerM", "cachedInputPerM", "reasoningPerM").forEach { field ->
                requireNumber(rates, field, "$path.$field", diagnostics)
            }
        }
    }

    private fun validateStepStructure(
        value: JsonElement,
        index: Int,
        diagnostics: MutableList<ValidationDiagnostic>,
    ) {
        val path = "$.credentialFlow.steps[$index]"
        val step = requireElementObject(value, path, diagnostics) ?: return
        listOf("id", "method", "path", "body").forEach { field ->
            requireString(step, field, "$path.$field", diagnostics)
        }
        requireObject(step, "headers", "$path.headers", diagnostics)
            ?.let { validateStringMap(it, "$path.headers", diagnostics) }
        requireArray(step, "expectedStatuses", "$path.expectedStatuses", diagnostics)
            ?.forEachIndexed { statusIndex, status ->
                if (!status.isJsonPrimitive || !status.asJsonPrimitive.isNumber) {
                    diagnostics += ValidationDiagnostic(
                        "$path.expectedStatuses[$statusIndex]",
                        "Expected status must be a JSON number",
                    )
                }
            }
        requireArray(step, "extracts", "$path.extracts", diagnostics)?.forEachIndexed { extractIndex, extractionValue ->
            val extractPath = "$path.extracts[$extractIndex]"
            val extraction = requireElementObject(extractionValue, extractPath, diagnostics) ?: return@forEachIndexed
            requireString(extraction, "name", "$extractPath.name", diagnostics)
            requireString(extraction, "jsonPath", "$extractPath.jsonPath", diagnostics)
            requireBoolean(extraction, "durable", "$extractPath.durable", diagnostics)
        }
    }

    private fun validateStringMap(
        value: JsonObject,
        path: String,
        diagnostics: MutableList<ValidationDiagnostic>,
    ) {
        value.entrySet().forEach { (key, element) ->
            if (element.isJsonNull || !element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                diagnostics += ValidationDiagnostic("$path.$key", "Map value must be a non-null string")
            }
        }
    }

    private fun validateNullableString(
        parent: JsonObject,
        field: String,
        path: String,
        diagnostics: MutableList<ValidationDiagnostic>,
    ) {
        val value = parent.get(field) ?: return
        if (!value.isJsonNull && (!value.isJsonPrimitive || !value.asJsonPrimitive.isString)) {
            diagnostics += ValidationDiagnostic(path, "Field must be null or a string")
        }
    }

    private fun requireString(
        parent: JsonObject,
        field: String,
        path: String,
        diagnostics: MutableList<ValidationDiagnostic>,
    ) {
        requireType(
            parent,
            field,
            path,
            { it.isJsonPrimitive && it.asJsonPrimitive.isString },
            diagnostics,
        )
    }

    private fun requireNumber(
        parent: JsonObject,
        field: String,
        path: String,
        diagnostics: MutableList<ValidationDiagnostic>,
    ) {
        requireType(
            parent,
            field,
            path,
            { it.isJsonPrimitive && it.asJsonPrimitive.isNumber },
            diagnostics,
        )
    }

    private fun requireBoolean(
        parent: JsonObject,
        field: String,
        path: String,
        diagnostics: MutableList<ValidationDiagnostic>,
    ) {
        requireType(
            parent,
            field,
            path,
            { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean },
            diagnostics,
        )
    }

    private fun requireObject(
        parent: JsonObject,
        field: String,
        path: String,
        diagnostics: MutableList<ValidationDiagnostic>,
    ): JsonObject? {
        val value = parent.get(field)
        if (value == null || value.isJsonNull || !value.isJsonObject) {
            diagnostics += ValidationDiagnostic(path, "Field must be a non-null object")
            return null
        }
        return value.asJsonObject
    }

    private fun requireArray(
        parent: JsonObject,
        field: String,
        path: String,
        diagnostics: MutableList<ValidationDiagnostic>,
    ): Iterable<JsonElement>? {
        val value = parent.get(field)
        if (value == null || value.isJsonNull || !value.isJsonArray) {
            diagnostics += ValidationDiagnostic(path, "Field must be a non-null array")
            return null
        }
        return value.asJsonArray
    }

    private fun requireElementObject(
        value: JsonElement,
        path: String,
        diagnostics: MutableList<ValidationDiagnostic>,
    ): JsonObject? {
        if (value.isJsonNull || !value.isJsonObject) {
            diagnostics += ValidationDiagnostic(path, "Array element must be a non-null object")
            return null
        }
        return value.asJsonObject
    }

    private fun requireType(
        parent: JsonObject,
        field: String,
        path: String,
        predicate: (JsonElement) -> Boolean,
        diagnostics: MutableList<ValidationDiagnostic>,
    ) {
        val value = parent.get(field)
        if (value == null || value.isJsonNull || !predicate(value)) {
            diagnostics += ValidationDiagnostic(path, "Field is missing, null, or has the wrong type")
        }
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
        validateUniqueIds(
            profile.credentialFlow.steps.map { it.id },
            "$.credentialFlow.steps",
            "Credential step ids must be unique",
            diagnostics,
        )

        profile.settings.forEachIndexed { index, setting ->
            if (!DECLARED_ID.matches(setting.id)) {
                diagnostics += ValidationDiagnostic(
                    "$.settings[$index].id",
                    "Setting id must match [A-Za-z_][A-Za-z0-9_.-]*",
                )
            }
        }
        profile.credentialFlow.inputs.forEachIndexed { index, input ->
            if (!DECLARED_ID.matches(input.id)) {
                diagnostics += ValidationDiagnostic(
                    "$.credentialFlow.inputs[$index].id",
                    "Credential input id must match [A-Za-z_][A-Za-z0-9_.-]*",
                )
            }
        }
        profile.credentialFlow.steps.forEachIndexed { index, step ->
            if (!DECLARED_ID.matches(step.id)) {
                diagnostics += ValidationDiagnostic(
                    "$.credentialFlow.steps[$index].id",
                    "Credential step id must match [A-Za-z_][A-Za-z0-9_.-]*",
                )
            }
        }
        validateGloballyDisjointIds(profile, diagnostics)

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

            step.extracts.forEachIndexed { extractIndex, extraction ->
                val extractPath = "$stepPath.extracts[$extractIndex]"
                if (extraction.name.isBlank()) {
                    diagnostics += ValidationDiagnostic("$extractPath.name", "Extraction name is required")
                } else if (!DECLARED_ID.matches(extraction.name)) {
                    diagnostics += ValidationDiagnostic(
                        "$extractPath.name",
                        "Extraction name must match [A-Za-z_][A-Za-z0-9_.-]*",
                    )
                } else if (!priorExtractions.add(extraction.name)) {
                    diagnostics += ValidationDiagnostic(
                        "$extractPath.name",
                        "Extraction names must be globally unique",
                    )
                }
                validateJsonPath("$extractPath.jsonPath", extraction.jsonPath, diagnostics)
            }
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

    private fun validateGloballyDisjointIds(
        profile: OpenAiCompatibleProfile,
        diagnostics: MutableList<ValidationDiagnostic>,
    ) {
        val declarations = buildList {
            profile.settings.forEachIndexed { index, setting ->
                add(setting.id to "$.settings[$index].id")
            }
            profile.credentialFlow.inputs.forEachIndexed { index, input ->
                add(input.id to "$.credentialFlow.inputs[$index].id")
            }
            profile.credentialFlow.steps.forEachIndexed { stepIndex, step ->
                add(step.id to "$.credentialFlow.steps[$stepIndex].id")
                step.extracts.forEachIndexed { extractIndex, extraction ->
                    add(extraction.name to "$.credentialFlow.steps[$stepIndex].extracts[$extractIndex].name")
                }
            }
        }
        declarations
            .filter { (id, _) -> id.isNotBlank() }
            .groupBy { (id, _) -> id }
            .filterValues { it.size > 1 }
            .forEach { (id, matches) ->
                matches.forEach { (_, path) ->
                    diagnostics += ValidationDiagnostic(
                        path,
                        "Declared id '$id' must be globally unique across settings and credential fields",
                    )
                }
            }
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
        host != null && LOCALHOST_HOSTS.contains(host.lowercase().removeSurrounding("[", "]"))

    private fun validateScheme(
        uri: URI,
        allowLocalHttp: Boolean,
        diagnostics: MutableList<ValidationDiagnostic>,
        path: String = "$.baseUrl",
    ) {
        val scheme = uri.scheme?.lowercase()
        when {
            uri.host.isNullOrBlank() || uri.rawAuthority.isNullOrBlank() ->
                diagnostics += ValidationDiagnostic(path, "Endpoint must include a valid host and authority")
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
            val start = value.indexOf("\${", index)
            if (start < 0) break
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
        listOf(profile.endpoints.models, profile.endpoints.chatCompletions).forEach { endpoint ->
            resolveEndpoint(baseUri, endpoint)?.host?.let { hosts += it }
        }
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
