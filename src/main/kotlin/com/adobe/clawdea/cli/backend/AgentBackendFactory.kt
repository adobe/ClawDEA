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
package com.adobe.clawdea.cli.backend

import com.adobe.clawdea.chat.permission.PermissionRouterRegistry
import com.adobe.clawdea.cli.CliEventParser
import com.adobe.clawdea.cli.CliProcess
import com.adobe.clawdea.cli.CodexAppServerParser
import com.adobe.clawdea.cli.CodexAppServerProcess
import com.adobe.clawdea.cli.UnavailableAgentProcess
import com.adobe.clawdea.mcp.McpServer
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.provider.ProviderRegistry
import com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore
import com.adobe.clawdea.provider.openai.catalog.ModelCapability
import com.adobe.clawdea.provider.openai.catalog.ModelCapabilityResolver
import com.adobe.clawdea.provider.openai.profile.ProfileStore
import com.adobe.clawdea.provider.openai.tools.MissingRouteBehavior
import com.adobe.clawdea.provider.openai.tools.SharedToolApprovalGate
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.project.Project
import java.io.File
import java.net.URI

/**
 * Factory for creating [AgentBackend] instances based on provider backend kind.
 * Replaces [CliBridge.backendSelection]'s construction logic.
 */
object AgentBackendFactory {

    /**
     * Creates an [AgentBackend] for the given [AgentSelection]. The backend kind is resolved from
     * [ProviderRegistry], and provider/profile/model come from the selection (no env-fallthrough).
     */
    fun create(
        selection: AgentSelection,
        workingDirectory: String = "",
        mcpPort: Int = 0,
        project: Project? = null,
        // Test seam: inject settings so the HTTP branch can run fully headless (no platform
        // Application). Production passes the application-service singleton.
        settings: ClawDEASettings = ClawDEASettings.getInstance(),
        // Test seam: inject the credential store. Production uses the default (PasswordSafe-backed).
        // NOTE: the factory MUST NOT call `.get(...)` here — it is invoked on the EDT during backend
        // rebuild, where PasswordSafe I/O is prohibited. The store is only wrapped in a lazy provider
        // that the backend evaluates off the EDT on first turn.
        credentialStore: ProfileCredentialStore = ProfileCredentialStore(),
    ): AgentBackend {
        val providerId = selection.providerId
        val kind = ProviderRegistry.require(providerId).backendKind
        return when (kind) {
            BackendKind.CLAUDE_CLI -> ProcessAgentBackend(
                // Source `--model` from THIS selection's provider, not the global effective provider,
                // so a per-tab Claude tab reads the Claude-pinned model even when the global default
                // is a Codex/OpenAI provider (which would otherwise yield an invalid-model failure).
                // The selection's pinned model wins so the dropdown label and the launched model agree
                // (a fresh chat seeded from the Roles tab has no selectedModels entry to read).
                process = CliProcess(
                    workingDirectory,
                    mcpPort,
                    project,
                    providerIdProvider = { providerId },
                    pinnedModelId = selection.modelId,
                ),
                parser = CliEventParser(),
                steeringMode = SteeringMode.NONE,
                backendKind = BackendKind.CLAUDE_CLI,
                agentLabel = "Claude"
            )
            BackendKind.CODEX_APP_SERVER -> ProcessAgentBackend(
                // Source the model + auth mode from THIS selection's provider (symmetric with the
                // Claude branch): a per-tab Codex tab reads the Codex-pinned model and picks ChatGPT
                // auth iff its own provider is openai-subscription — even when the global differs.
                // The selection's pinned model wins so the dropdown label and the launched model
                // agree (a fresh chat seeded from the Roles tab has no selectedModels entry to read).
                process = CodexAppServerProcess(
                    workingDirectory,
                    mcpPort,
                    project,
                    modelProvider = {
                        selection.modelId.ifBlank {
                            ClawDEASettings.getInstance().getCliModelId(workingDirectory, providerId)
                        }
                    },
                    forceChatGptAuthProvider = { providerId == "openai-subscription" },
                ),
                parser = CodexAppServerParser(
                    selection.modelId.ifBlank {
                        ClawDEASettings.getInstance().getCliModelId(workingDirectory, providerId)
                    },
                ),
                steeringMode = SteeringMode.NATIVE,
                backendKind = BackendKind.CODEX_APP_SERVER,
                agentLabel = "Codex"
            )
            BackendKind.OPENAI_COMPATIBLE_HTTP -> {
                // Simplified: create backend with readinessError if any prerequisite missing
                val profileStore = ProfileStore(settings)
                val agentLabel = ProviderRegistry.require(providerId).displayLabel
                val projectPath = workingDirectory.ifEmpty { project?.basePath ?: System.getProperty("user.dir") }

                // Helper to create error backend
                fun errorBackend(message: String): OpenAiCompatibleAgentBackend {
                    // Create stub instances for required params (will not be used since readinessError is set)
                    val stubProfile = com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile(
                        profile = com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile(
                            id = providerId,
                            name = agentLabel,
                        ),
                        baseUrl = URI("http://stub"),
                        configuredValues = emptyMap(),
                    )
                    val stubGate = SharedToolApprovalGate(
                        toolApprovalMode = { "allow-all" },
                        policy = { null },
                        route = { _, _, _ -> null },
                        promptTimeoutMs = 0,
                    )
                    return OpenAiCompatibleAgentBackend(
                        profile = stubProfile,
                        credentialProvider = { "" },
                        modelIdProvider = { "" },
                        project = project,
                        projectPath = projectPath,
                        mcpDefs = emptyList(),
                        approvalGate = stubGate,
                        autoAcceptEdits = { false },
                        fallbackAgentLabel = agentLabel,
                        readinessError = message,
                    )
                }

                // Get profile ID from the selection (explicit pick, no fallback)
                val activeProfileId = selection.profileId ?: ""
                if (activeProfileId.isBlank()) {
                    return errorBackend("No OpenAI-compatible profile selected")
                }

                // Resolve profile
                val profileEntry = profileStore.resolve(activeProfileId, System.getenv())
                    ?: return errorBackend("Profile '$activeProfileId' not configured")

                // Get selected model from the selection (explicit pick, no fallback)
                val catalogKey = selection.catalogKey()
                val selectedModelId = selection.modelId.ifBlank { null }
                if (selectedModelId.isNullOrBlank()) {
                    return errorBackend("No model selected for profile '$activeProfileId'")
                }

                // Check capability
                val capability = ModelCapabilityResolver.resolve(
                    modelId = selectedModelId,
                    endpointCapability = null,
                    profileRules = profileEntry.profile.modelRules,
                    userOverride = null,
                )
                if (capability != ModelCapability.AGENTIC) {
                    return errorBackend("Model $selectedModelId is not agentic (capability: $capability)")
                }

                // Build tool catalog and approval gate
                val mcpDefs = if (project != null) {
                    McpServer.getInstance(project).toolDefinitions()
                } else {
                    emptyList()
                }

                val approvalGate = SharedToolApprovalGate(
                    toolApprovalMode = { settings.state.toolApprovalMode },
                    policy = { null }, // TODO: wire permission policy
                    route = { toolName, inputJson, toolUseId ->
                        if (project != null) {
                            PermissionRouterRegistry(project).route(toolName, inputJson, toolUseId)
                        } else {
                            null
                        }
                    },
                    promptTimeoutMs = 300_000,
                )

                OpenAiCompatibleAgentBackend(
                    profile = profileEntry,
                    // Lazy: the backend reads the credential off the EDT on first turn. The factory
                    // runs on the EDT during rebuild, where a PasswordSafe read would throw.
                    credentialProvider = { credentialStore.get(activeProfileId) },
                    // The selection has already captured the model ID; return it directly.
                    // (No re-read: the caller rebuilds the backend on model change.)
                    modelIdProvider = { selectedModelId },
                    project = project,
                    projectPath = projectPath,
                    mcpDefs = mcpDefs,
                    approvalGate = approvalGate,
                    autoAcceptEdits = { settings.state.autoAcceptEdits },
                    fallbackAgentLabel = agentLabel,
                )
            }
        }
    }

    /**
     * Legacy overload: creates an [AgentBackend] for the given provider ID, reading the active
     * profile and selected model from current settings. Delegates to [create(selection, ...)].
     * All existing callers use this overload and compile unchanged.
     */
    fun create(
        providerId: String,
        workingDirectory: String = "",
        mcpPort: Int = 0,
        project: Project? = null,
        settings: ClawDEASettings = ClawDEASettings.getInstance(),
        credentialStore: ProfileCredentialStore = ProfileCredentialStore(),
    ): AgentBackend {
        // Build an AgentSelection from current settings. Only the openai-compatible provider
        // reads profile + selected model here; Claude ignores selection.modelId (CLAUDE_CLI branch)
        // and Codex resolves its own model via getCliModelId in the CODEX_APP_SERVER branch, so
        // computing a model for them would be a dead, misleading lookup.
        val selection = if (providerId == ProviderRegistry.OPENAI_COMPATIBLE_ID) {
            val profileId = settings.state.activeOpenAiCompatibleProfileId
            val catalogKey = ProviderRegistry.catalogKey(providerId, profileId)
            val modelId = settings.getSelectedModelId(workingDirectory, catalogKey) ?: ""
            AgentSelection(providerId, profileId.ifBlank { null }, modelId)
        } else {
            AgentSelection(providerId) // profileId=null, modelId="" — Claude/Codex resolve model in their own branch
        }
        return create(selection, workingDirectory, mcpPort, project, settings, credentialStore)
    }
}
