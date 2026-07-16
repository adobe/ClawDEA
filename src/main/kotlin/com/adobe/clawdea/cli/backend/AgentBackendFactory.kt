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

import com.adobe.clawdea.cli.CliEventParser
import com.adobe.clawdea.cli.CliProcess
import com.adobe.clawdea.cli.CodexAppServerParser
import com.adobe.clawdea.cli.CodexAppServerProcess
import com.adobe.clawdea.cli.UnavailableAgentProcess
import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.provider.ProviderRegistry
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.project.Project

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
            BackendKind.OPENAI_COMPATIBLE_HTTP -> ProcessAgentBackend(
                process = UnavailableAgentProcess("OpenAI-compatible HTTP agent backend is not available yet."),
                parser = CliEventParser(),
                steeringMode = SteeringMode.NONE,
                backendKind = BackendKind.OPENAI_COMPATIBLE_HTTP,
                agentLabel = ProviderRegistry.require(providerId).displayLabel
            )
        }
    }
}
