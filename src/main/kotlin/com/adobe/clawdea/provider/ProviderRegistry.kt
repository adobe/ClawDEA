package com.adobe.clawdea.provider

object ProviderRegistry {
    const val OPENAI_COMPATIBLE_ID = "openai-compatible"

    private val descriptors = listOf(
        ProviderDescriptor("anthropic", "Anthropic", BackendKind.CLAUDE_CLI, AuthStrategy.API_KEY, true, true, true),
        ProviderDescriptor("bedrock", "Bedrock", BackendKind.CLAUDE_CLI, AuthStrategy.ENVIRONMENT, true, true, true),
        ProviderDescriptor("vertex", "Vertex", BackendKind.CLAUDE_CLI, AuthStrategy.ENVIRONMENT, true, true, true),
        ProviderDescriptor("subscription", "Claude", BackendKind.CLAUDE_CLI, AuthStrategy.CLAUDE_LOGIN, true, true, true),
        ProviderDescriptor("openai", "Codex", BackendKind.CODEX_APP_SERVER, AuthStrategy.API_KEY, false, false, false),
        ProviderDescriptor("openai-subscription", "Codex", BackendKind.CODEX_APP_SERVER, AuthStrategy.CODEX_LOGIN, false, false, false),
        ProviderDescriptor(OPENAI_COMPATIBLE_ID, "OpenAI-compatible", BackendKind.OPENAI_COMPATIBLE_HTTP, AuthStrategy.PROFILE_CREDENTIAL_FLOW, true, true, false),
    ).associateBy(ProviderDescriptor::id)

    fun descriptor(id: String): ProviderDescriptor? = descriptors[id]
    fun require(id: String): ProviderDescriptor = descriptors[id] ?: descriptors.getValue("anthropic")
    fun all(): List<ProviderDescriptor> = descriptors.values.toList()
    fun isCodex(id: String): Boolean = require(id).backendKind == BackendKind.CODEX_APP_SERVER
    fun catalogKey(providerId: String, profileId: String): String =
        if (providerId == OPENAI_COMPATIBLE_ID && profileId.isNotBlank()) "$providerId:$profileId" else providerId
}
