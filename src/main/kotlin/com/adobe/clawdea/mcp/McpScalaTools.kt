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

import com.adobe.clawdea.language.scala.ScalaPsiBridge
import com.adobe.clawdea.util.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project

/**
 * MCP tools that exist only for Scala — registered conditionally by [McpServer] when
 * [ScalaPsiBridge] is available (i.e. the IntelliJ Scala plugin is installed).
 *
 * Currently registers a single tool, `find_implicit_definitions`, which lists `given`,
 * `implicit val/def`, and `extension` declarations in a Scala file. File-scoped only —
 * no cross-file resolution. See spec `2026-05-24-scala-specific-mcp-tools-design.md`.
 *
 * Constructor takes [ScalaPsiBridge] by injection rather than looking it up internally
 * so this class is unit-testable without IntelliJ infrastructure.
 */
class McpScalaTools(
    private val project: Project,
    private val bridge: ScalaPsiBridge,
) {

    private val log = Logger.getInstance(McpScalaTools::class.java)

    fun registerAll(router: McpToolRouter) {
        router.register(
            name = "find_implicit_definitions",
            description = "List all given declarations, implicit val/def, and extension blocks in a Scala file. " +
                "Returns each entry with its 1-based line number and a one-line source signature. " +
                "Scala-specific — only available when the IntelliJ Scala plugin is installed.",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative .scala file path"),
            ),
            required = listOf("file"),
            handler = ::findImplicitDefinitions,
        )
    }

    private fun findImplicitDefinitions(args: Map<String, String>): McpToolRouter.ToolResult {
        val file = args["file"] ?: return McpToolRouter.ToolResult(
            "Missing 'file' argument", isError = true,
        )
        if (DumbService.isDumb(project)) return McpToolRouter.ToolResult(
            "Indexing in progress, try again shortly.", isError = true,
        )
        val psiFile = PsiUtils.resolvePsiFile(project, file) ?: return McpToolRouter.ToolResult(
            "File not found: $file", isError = true,
        )

        val result = runReadAction {
            try {
                bridge.findImplicitDefinitions(psiFile)
            } catch (e: Throwable) {
                log.warn("find_implicit_definitions: bridge threw ${e.javaClass.simpleName}: ${e.message}", e)
                "find_implicit_definitions failed: ${e.javaClass.simpleName}: ${e.message}"
            }
        }

        return when {
            result == null -> McpToolRouter.ToolResult("Not a Scala file.", isError = false)
            else -> McpToolRouter.ToolResult(result)
        }
    }
}
