package com.adobe.clawdea.provider

object ProviderRegistry {
    const val OPENAI_COMPATIBLE_ID = "openai-compatible"

    // Declaration order IS the Settings → Providers combo order (see [orderedIds]).
    private val descriptors = listOf(
        ProviderDescriptor("anthropic", "Anthropic", "Anthropic (direct)", BackendKind.CLAUDE_CLI, AuthStrategy.API_KEY, true, true, true),
        ProviderDescriptor("bedrock", "Bedrock", "Amazon Bedrock", BackendKind.CLAUDE_CLI, AuthStrategy.ENVIRONMENT, true, true, true),
        ProviderDescriptor("vertex", "Vertex", "Google Vertex AI", BackendKind.CLAUDE_CLI, AuthStrategy.ENVIRONMENT, true, true, true),
        ProviderDescriptor("subscription", "Claude", "Claude subscription (Pro / Max / Team / Enterprise)", BackendKind.CLAUDE_CLI, AuthStrategy.CLAUDE_LOGIN, true, true, true),
        ProviderDescriptor("openai", "Codex", "OpenAI (direct)", BackendKind.CODEX_APP_SERVER, AuthStrategy.API_KEY, false, false, false),
        ProviderDescriptor("openai-subscription", "Codex", "OpenAI (ChatGPT subscription)", BackendKind.CODEX_APP_SERVER, AuthStrategy.CODEX_LOGIN, false, false, false),
        ProviderDescriptor(OPENAI_COMPATIBLE_ID, "OpenAI-compatible", "OpenAI-compatible", BackendKind.OPENAI_COMPATIBLE_HTTP, AuthStrategy.PROFILE_CREDENTIAL_FLOW, true, true, false),
    ).associateBy(ProviderDescriptor::id)

    fun descriptor(id: String): ProviderDescriptor? = descriptors[id]
    fun require(id: String): ProviderDescriptor = descriptors[id] ?: descriptors.getValue("anthropic")
    fun all(): List<ProviderDescriptor> = descriptors.values.toList()

    /** Provider ids in Settings-combo order. Index-aligned with [settingsLabels]. */
    fun orderedIds(): List<String> = descriptors.values.map { it.id }

    /** Long Settings-combo labels. Index-aligned with [orderedIds]. */
    fun settingsLabels(): List<String> = descriptors.values.map { it.settingsLabel }

    fun isCodex(id: String): Boolean = require(id).backendKind == BackendKind.CODEX_APP_SERVER
    fun catalogKey(providerId: String, profileId: String): String =
        if (providerId == OPENAI_COMPATIBLE_ID && profileId.isNotBlank()) "$providerId:$profileId" else providerId
}
