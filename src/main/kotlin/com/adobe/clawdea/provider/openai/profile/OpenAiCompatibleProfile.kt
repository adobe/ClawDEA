package com.adobe.clawdea.provider.openai.profile

data class OpenAiCompatibleProfile(
    val schemaVersion: Int = 1,
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val baseUrl: String = "",
    val endpoints: EndpointConfig = EndpointConfig(),
    val headers: Map<String, String> = emptyMap(),
    val settings: List<ProfileSetting> = emptyList(),
    val credentialFlow: CredentialFlow = CredentialFlow(),
    val modelMapping: ModelMapping = ModelMapping(),
    val modelRules: List<ModelRule> = emptyList(),
    val pricing: Map<String, TokenRates> = emptyMap(),
    // Per-model context window in tokens, keyed by model id (mirrors [pricing]). Empty/absent means
    // the window is unknown for that model; the agent loop then falls back to a char-based compaction
    // budget. Gson deserialization of profiles lacking the field yields an empty map.
    val contextWindows: Map<String, Int> = emptyMap(),
    // When false, the agent backend requests non-streamed completions (`stream:false`, and omits the
    // streaming-only `stream_options`). Defaults true so existing profiles and Gson deserialization of
    // profiles lacking the field keep the historical streaming behavior. Set false for gateways that
    // mishandle `stream:true` (e.g. return HTTP 200 with a non-JSON/SSE error body).
    val streaming: Boolean = true,
)

data class EndpointConfig(
    val models: String = "/models",
    val chatCompletions: String = "/chat/completions",
)

data class ProfileSetting(
    val id: String = "",
    val label: String = "",
    val environmentVariable: String? = null,
    val required: Boolean = false,
    val defaultValue: String = "",
)

data class CredentialFlow(
    val inputs: List<CredentialInput> = emptyList(),
    val steps: List<CredentialStep> = emptyList(),
    val durableCredential: String = "",
)

data class CredentialInput(val id: String = "", val label: String = "", val secret: Boolean = false)

data class CredentialStep(
    val id: String = "",
    val method: String = "POST",
    val path: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val expectedStatuses: List<Int> = listOf(200),
    val extracts: List<ResponseExtraction> = emptyList(),
)

data class ResponseExtraction(val name: String = "", val jsonPath: String = "", val durable: Boolean = false)

data class ModelMapping(
    val arrayPath: String = "$.data",
    val idPath: String = "$.id",
    val displayNamePath: String = "$.id",
)

data class ModelRule(val pattern: String = "", val capability: String = "completion-only")

data class TokenRates(
    val inputPerM: Double = 0.0,
    val outputPerM: Double = 0.0,
    val cachedInputPerM: Double = 0.0,
    val reasoningPerM: Double = 0.0,
)
