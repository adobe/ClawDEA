package com.adobe.clawdea.provider

enum class BackendKind { CLAUDE_CLI, CODEX_APP_SERVER, OPENAI_COMPATIBLE_HTTP }
enum class AuthStrategy { API_KEY, ENVIRONMENT, CLAUDE_LOGIN, CODEX_LOGIN, PROFILE_CREDENTIAL_FLOW }

data class ProviderDescriptor(
    val id: String,
    val displayLabel: String,
    val backendKind: BackendKind,
    val authStrategy: AuthStrategy,
    val supportsInlineCompletions: Boolean,
    val supportsIntentionActions: Boolean,
    val allowEnvironmentFallback: Boolean,
)
