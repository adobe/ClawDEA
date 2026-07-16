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
     * Creates an [AgentBackend] for the given provider. The backend kind is resolved from
     * [ProviderRegistry], and the appropriate process + parser + steering mode are selected.
     */
    fun create(
        providerId: String,
        workingDirectory: String = "",
        mcpPort: Int = 0,
        project: Project? = null,
    ): AgentBackend {
        val kind = ProviderRegistry.require(providerId).backendKind
        return when (kind) {
            BackendKind.CLAUDE_CLI -> ProcessAgentBackend(
                process = CliProcess(workingDirectory, mcpPort, project),
                parser = CliEventParser(),
                steeringMode = SteeringMode.NONE,
                backendKind = BackendKind.CLAUDE_CLI,
                agentLabel = "Claude"
            )
            BackendKind.CODEX_APP_SERVER -> ProcessAgentBackend(
                process = CodexAppServerProcess(workingDirectory, mcpPort, project),
                parser = CodexAppServerParser(ClawDEASettings.getInstance().getCliModelId(workingDirectory, providerId)),
                steeringMode = SteeringMode.NATIVE,
                backendKind = BackendKind.CODEX_APP_SERVER,
                agentLabel = "Codex"
            )
            BackendKind.OPENAI_COMPATIBLE_HTTP -> {
                // Simplified: create backend with readinessError if any prerequisite missing
                val settings = ClawDEASettings.getInstance()
                val profileStore = ProfileStore(settings)
                val credentialStore = ProfileCredentialStore()
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
                        credential = "",
                        modelId = "",
                        project = project,
                        projectPath = projectPath,
                        mcpDefs = emptyList(),
                        approvalGate = stubGate,
                        autoAcceptEdits = { false },
                        agentLabel = agentLabel,
                        readinessError = message,
                    )
                }

                // Resolve profile
                val profileEntry = profileStore.resolve(providerId, System.getenv())
                    ?: return errorBackend("Profile not configured for $providerId")

                // Get credential
                val credential = credentialStore.get(providerId)
                    ?: return errorBackend("Credential not configured for $providerId")

                // Get selected model
                val catalogKey = ProviderRegistry.catalogKey(providerId, providerId)
                val selectedModelId = settings.getSelectedModelId(workingDirectory, catalogKey)
                    ?: return errorBackend("No model selected for $providerId")

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
                    credential = credential,
                    modelId = selectedModelId,
                    project = project,
                    projectPath = projectPath,
                    mcpDefs = mcpDefs,
                    approvalGate = approvalGate,
                    autoAcceptEdits = { settings.state.autoAcceptEdits },
                    agentLabel = agentLabel,
                )
            }
        }
    }
}
