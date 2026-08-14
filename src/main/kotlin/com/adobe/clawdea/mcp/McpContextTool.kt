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

import com.adobe.clawdea.util.runReadAction

import com.adobe.clawdea.context.ContextEngine
import com.adobe.clawdea.context.ContextProfile
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

/**
 * MCP tool handler for get_project_context.
 * Wraps ContextEngine.gatherContext() for the CHAT profile.
 */
class McpContextTool(private val project: Project) {

    fun registerAll(router: McpToolRouter) {
        router.register(
            name = "get_project_context",
            description = "Get a curated context dump for the given file and line, including current class, method, imports, supertypes, callers, implementations, usages, related types, open files, git changes, and recent commits. Uses the full CHAT context profile.",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative file path"),
                Triple("line", "string", "1-based line number (optional, defaults to 1)"),
            ),
            required = listOf("file"),
            handler = ::getProjectContext,
        )
    }

    private fun getProjectContext(args: Map<String, String>): McpToolRouter.ToolResult {
        val filePath = args["file"] ?: return McpToolRouter.ToolResult("Missing 'file' argument", isError = true)
        val line = args["line"]?.toIntOrNull() ?: 1

        if (DumbService.isDumb(project)) {
            return McpToolRouter.ToolResult("Indexing in progress, try again shortly.", isError = true)
        }

        val psiFile = PsiUtils.resolvePsiFile(project, filePath)
            ?: return McpToolRouter.ToolResult("File not found: $filePath", isError = true)

        // Resolve the line to an offset from the file's own Document — no Editor needed, so this works
        // for any file (not just the active editor) and never mutates the shared editor caret from the
        // MCP dispatch thread (Tier 6.3).
        val result = runReadAction {
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@runReadAction "Could not load a document for $filePath"
            val lineIndex = (line - 1).coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
            val offset = document.getLineStartOffset(lineIndex)
            ContextEngine.getInstance(project).gatherContextAt(psiFile, offset, ContextProfile.CHAT)
        }

        return McpToolRouter.ToolResult(result)
    }
}
