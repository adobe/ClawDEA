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
package com.adobe.clawdea.mcp

import com.adobe.clawdea.knowledge.primer.PrimerService
import com.intellij.openapi.project.Project

class McpPrimerTool(private val project: Project) {

    fun registerAll(router: McpToolRouter) {
        router.register(
            name = TOOL_NAME,
            description = TOOL_DESCRIPTION,
            properties = emptyList(),
            required = emptyList(),
            handler = ::getPrimer,
        )
    }

    private fun getPrimer(@Suppress("UNUSED_PARAMETER") args: Map<String, String>): McpToolRouter.ToolResult {
        val text = PrimerService.getInstance(project).refreshAndGet()
        return if (text.isBlank()) {
            McpToolRouter.ToolResult(
                "(no primer available — knowledge layer disabled, project has no basePath, " +
                "or no CLAUDE.md / MAP signals)",
            )
        } else {
            McpToolRouter.ToolResult(text)
        }
    }

    companion object {
        const val TOOL_NAME = "get_primer"
        const val TOOL_DESCRIPTION =
            "Return the assembled project primer (CLAUDE.md + auto-generated " +
            "module map and current focus). Use at the start of a session to " +
            "orient yourself in the project without manual exploration."
    }
}
