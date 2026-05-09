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
package com.adobe.clawdea.auth

import com.adobe.clawdea.settings.ClawDEASettings

class VertexAuthProvider(
    private val region: () -> String,
    private val projectId: () -> String,
    private val envRegion: () -> String?,
    private val envProjectId: () -> String?,
) : AuthProvider {
    override val id = "vertex"

    constructor() : this(
        region = { ClawDEASettings.getInstance().state.vertexRegion },
        projectId = { ClawDEASettings.getInstance().state.vertexProjectId },
        envRegion = { System.getenv("CLOUD_ML_REGION") },
        envProjectId = { System.getenv("ANTHROPIC_VERTEX_PROJECT_ID") },
    )

    constructor(region: String, projectId: String) : this(
        region = { region },
        projectId = { projectId },
        envRegion = { null },
        envProjectId = { null },
    )

    override fun isConfigured(): Boolean =
        region().isNotBlank() || projectId().isNotBlank() ||
            !envRegion().isNullOrBlank() || !envProjectId().isNullOrBlank()

    override fun applyToEnvironment(env: MutableMap<String, String>) {
        env["CLAUDE_CODE_USE_VERTEX"] = "1"
        val r = region()
        val p = projectId()
        if (r.isNotBlank()) env["CLOUD_ML_REGION"] = r
        if (p.isNotBlank()) env["ANTHROPIC_VERTEX_PROJECT_ID"] = p
    }

    override fun validate(): AuthValidation = if (isConfigured()) {
        AuthValidation(valid = true, message = null)
    } else {
        AuthValidation(valid = false, message = "Vertex AI not configured. Set region and project ID in Settings > Tools > ClawDEA, or export CLAUDE_CODE_USE_VERTEX=1 in your shell.")
    }

    override fun testConnection(): ConnectionTestResult =
        CliConnectionProbe.probe(this)
}
