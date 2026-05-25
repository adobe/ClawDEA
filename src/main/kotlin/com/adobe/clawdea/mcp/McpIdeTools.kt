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

import com.adobe.clawdea.buildtool.BuildTool
import com.adobe.clawdea.buildtool.BuildToolRegistry
import com.adobe.clawdea.buildtool.CompileCommand
import com.adobe.clawdea.language.LanguageSupportRegistry
import com.adobe.clawdea.mcp.diagnostics.CompilerManagerDiagnostics
import com.adobe.clawdea.util.runReadAction

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * MCP tool handlers for IDE capabilities: get_diagnostics, resolve_symbol.
 *
 * `get_diagnostics` uses a four-tier strategy, preferring IntelliJ-native analysis
 * and only shelling out to an external build tool as a safety net:
 *
 * 1. **DaemonCodeAnalyzer cached highlights** — when the file is open in an editor.
 * 2. **InspectionEngine** — when the file is closed; runs every enabled
 *    LocalInspectionTool's `checkFile`. Returns the formatted result, or the
 *    `"No diagnostics found."` sentinel for a clean file. Null is reserved
 *    exclusively for the caught-exception path (analyzer couldn't run).
 * 3. **CompilerManager.compile** ([CompilerManagerDiagnostics]) — IntelliJ's native
 *    compile API; uses the synced project model. Async-to-sync wrapped with a 60s
 *    timeout. Reached only when tier 2 returned null.
 * 4. **Build-tool subprocess** ([getDiagnosticsViaBuildTool]) — external gradle /
 *    mvn / sbt / mill invocation. Reached only when tier 3 returns NotApplicable /
 *    Aborted / Failed.
 */
class McpIdeTools(private val project: Project) {

    private val log = Logger.getInstance(McpIdeTools::class.java)

    fun registerAll(router: McpToolRouter) {
        router.register(
            name = "get_diagnostics",
            description = "Get compiler errors and warnings for a file (or all open files if file is omitted). Returns severity, message, file path, and line number.",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative file path (optional — omit for all open files)"),
            ),
            handler = ::getDiagnostics,
        )
        router.register(
            name = "resolve_symbol",
            description = "Go to definition: resolve the symbol at the given file, line, and column to its definition location. Returns the target file path, line number, and surrounding code.",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative file path"),
                Triple("line", "string", "1-based line number"),
                Triple("column", "string", "1-based column number"),
            ),
            required = listOf("file", "line", "column"),
            handler = ::resolveSymbol,
        )
    }

    private fun getDiagnostics(args: Map<String, String>): McpToolRouter.ToolResult {
        val filePath = args["file"]

        if (DumbService.isDumb(project)) {
            return McpToolRouter.ToolResult("Indexing in progress, try again shortly.", isError = true)
        }

        if (filePath != null) {
            return getDiagnosticsForFile(filePath)
        }

        // All open files
        val results = runReadAction {
            val sb = StringBuilder()
            val editorManager = FileEditorManager.getInstance(project)
            for (vf in editorManager.openFiles) {
                val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf)
                    ?: continue
                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                    ?: continue
                val path = PsiUtils.getFilePath(psiFile, project)
                collectHighlights(document, path, sb)
            }
            if (sb.isEmpty()) "No diagnostics found." else sb.toString()
        }
        return McpToolRouter.ToolResult(results)
    }

    private fun getDiagnosticsForFile(filePath: String): McpToolRouter.ToolResult {
        val psiFile = runReadAction {
            PsiUtils.resolvePsiFile(project, filePath)
        } ?: return McpToolRouter.ToolResult("File not found: $filePath", isError = true)

        val vf = psiFile.virtualFile
            ?: return McpToolRouter.ToolResult("No virtual file for: $filePath", isError = true)

        // If the file is open, the daemon has cached highlights — fast path.
        val editorManager = FileEditorManager.getInstance(project)
        if (editorManager.isFileOpen(vf)) {
            val results = runReadAction {
                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                    ?: return@runReadAction "Cannot get document for $filePath"
                val path = PsiUtils.getFilePath(psiFile, project)
                val sb = StringBuilder()
                collectHighlights(document, path, sb)
                if (sb.isEmpty()) "No diagnostics found." else sb.toString()
            }
            return McpToolRouter.ToolResult(results)
        }

        // tryLocalInspections returns null ONLY on caught exception. A clean file
        // returns the "No diagnostics found." sentinel — that path must NOT fall
        // through to the slow tier-3/4 fallbacks.
        val inspectionResult = tryLocalInspections(psiFile, filePath)
        if (inspectionResult != null) {
            return McpToolRouter.ToolResult(inspectionResult)
        }

        // Total budget for tiers 3+4 must fit inside McpServer's 60s tool timeout.
        // Reserve a small overhead margin and split evenly between tier 3 and tier 4.
        val deadlineMillis = System.currentTimeMillis() + TIER_3_4_BUDGET_MILLIS

        log.info("get_diagnostics: InspectionEngine failed for $filePath, trying CompilerManager")
        val tier3Budget = (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0L) / 2
        when (val compileResult = CompilerManagerDiagnostics.compileSync(project, vf, tier3Budget)) {
            is CompilerManagerDiagnostics.CompileSyncResult.Success ->
                return McpToolRouter.ToolResult(compileResult.diagnosticsText)
            is CompilerManagerDiagnostics.CompileSyncResult.AlreadyInProgress ->
                return McpToolRouter.ToolResult(
                    "Build is currently in progress, try again shortly.",
                    isError = true,
                )
            is CompilerManagerDiagnostics.CompileSyncResult.Timeout ->
                return McpToolRouter.ToolResult(
                    "CompilerManager timed out for $filePath after ${tier3Budget}ms. The compile may still be running; retry in a moment.",
                    isError = true,
                )
            is CompilerManagerDiagnostics.CompileSyncResult.NotApplicable,
            is CompilerManagerDiagnostics.CompileSyncResult.Aborted,
            is CompilerManagerDiagnostics.CompileSyncResult.Failed -> {
                log.info("get_diagnostics: CompilerManager ${compileResult.javaClass.simpleName} for $filePath, falling back to build tool")
            }
        }

        val tier4Budget = (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(MIN_TIER_4_BUDGET_MILLIS)
        return getDiagnosticsViaBuildTool(filePath, tier4Budget)
    }

    private fun tryLocalInspections(psiFile: com.intellij.psi.PsiFile, filePath: String): String? {
        return runReadAction {
            try {
                val path = PsiUtils.getFilePath(psiFile, project)
                val inspectionManager = com.intellij.codeInspection.InspectionManager.getInstance(project)
                val profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager
                    .getInstance(project).currentProfile
                val tools = profile.getAllEnabledInspectionTools(project)
                val sb = StringBuilder()

                for (toolWrapper in tools) {
                    val tool = toolWrapper.tool
                    if (tool !is com.intellij.codeInspection.LocalInspectionTool) continue
                    val problems = try {
                        tool.checkFile(psiFile, inspectionManager, false)
                    } catch (_: Exception) {
                        continue
                    } ?: continue
                    for (problem in problems) {
                        val psiElement = problem.psiElement ?: continue
                        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: continue
                        val lineNum = document.getLineNumber(psiElement.textOffset) + 1
                        val col = psiElement.textOffset - document.getLineStartOffset(lineNum - 1) + 1
                        val severity = when (problem.highlightType) {
                            com.intellij.codeInspection.ProblemHighlightType.ERROR,
                            com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR -> "ERROR"
                            com.intellij.codeInspection.ProblemHighlightType.WARNING,
                            com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> "WARNING"
                            else -> "WARNING"
                        }
                        sb.appendLine("$severity $path:$lineNum:$col ${problem.descriptionTemplate}")
                    }
                }

                // Empty must yield the sentinel (not null), so a clean file does
                // NOT trigger the slow tier-3/4 fallback. Null is reserved for the
                // caught-exception path below.
                if (sb.isEmpty()) "No diagnostics found." else sb.toString()
            } catch (e: Exception) {
                log.info("Local inspections failed for $filePath: ${e.message}")
                null
            }
        }
    }

    private fun getDiagnosticsViaBuildTool(filePath: String, budgetMillis: Long): McpToolRouter.ToolResult {
        val basePath = project.basePath
            ?: return McpToolRouter.ToolResult("No project base path", isError = true)

        val (buildTool, cmd) = when (val resolution = resolveCompileCommand(filePath, project)) {
            is CompileResolution.Ready -> resolution.buildTool to resolution.command
            is CompileResolution.EarlyResult -> return resolution.result
        }

        // Cap the build-tool subprocess at the smaller of (cmd.timeout, remaining
        // tier-4 budget) so we don't blow past the MCP tool envelope.
        val effectiveTimeoutMillis = minOf(cmd.timeout.inWholeMilliseconds, budgetMillis)
        return try {
            val process = ProcessBuilder(cmd.argv)
                .directory(cmd.workingDir)
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(effectiveTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                return McpToolRouter.ToolResult(
                    "${buildTool.displayName} compile timed out after ${effectiveTimeoutMillis}ms", isError = true,
                )
            }
            val output = process.inputStream.bufferedReader().readText()
            if (process.exitValue() == 0) {
                McpToolRouter.ToolResult("No diagnostics found.")
            } else {
                val relevant = buildTool.filterDiagnostics(output, filePath, basePath)
                if (relevant.isBlank()) {
                    McpToolRouter.ToolResult(output.take(2000))
                } else {
                    McpToolRouter.ToolResult(relevant)
                }
            }
        } catch (e: Exception) {
            McpToolRouter.ToolResult("${buildTool.displayName} compile failed: ${e.message}", isError = true)
        }
    }

    companion object {
        // Tiers 3 + 4 must complete inside the 60s MCP tool timeout. Reserve 5s for
        // overhead (process spawn, output read, error envelope) and split the rest.
        private const val TIER_3_4_BUDGET_MILLIS = 55_000L
        private const val MIN_TIER_4_BUDGET_MILLIS = 5_000L

        internal const val NO_BUILD_TOOL_MSG =
            "No build tool detected for this project; diagnostics fell back to in-IDE inspections only."

        internal fun unknownExtensionMsg(extension: String): String =
            "No language support known for file extension '.$extension'"

        internal fun unsupportedLanguageMsg(toolDisplayName: String, languageDisplayName: String): String =
            "$toolDisplayName does not support compiling $languageDisplayName in this project."

        internal sealed class CompileResolution {
            data class Ready(val buildTool: BuildTool, val command: CompileCommand) : CompileResolution()
            data class EarlyResult(val result: McpToolRouter.ToolResult) : CompileResolution()
        }

        /**
         * Pure decision: returns either an early [McpToolRouter.ToolResult]
         * (informational or error) or a (buildTool, command) pair to execute.
         */
        internal fun resolveCompileCommand(filePath: String, project: Project): CompileResolution {
            val buildTool = BuildToolRegistry.detectPrimary(project)
                ?: return CompileResolution.EarlyResult(McpToolRouter.ToolResult(NO_BUILD_TOOL_MSG))

            val extension = filePath.substringAfterLast('.', missingDelimiterValue = "")
            val languageSupport = LanguageSupportRegistry.forFileExtension(extension)
                ?: return CompileResolution.EarlyResult(
                    McpToolRouter.ToolResult(unknownExtensionMsg(extension), isError = true),
                )

            val cmd = buildTool.compileCommandFor(languageSupport, project)
                ?: return CompileResolution.EarlyResult(
                    McpToolRouter.ToolResult(
                        unsupportedLanguageMsg(buildTool.displayName, languageSupport.displayName),
                    ),
                )

            return CompileResolution.Ready(buildTool, cmd)
        }
    }

    private fun collectHighlights(
        document: com.intellij.openapi.editor.Document,
        path: String,
        sb: StringBuilder,
    ) {
        DaemonCodeAnalyzerEx.processHighlights(
            document, project, HighlightSeverity.WARNING, 0, document.textLength
        ) { info ->
            val lineNum = document.getLineNumber(info.startOffset) + 1
            val col = info.startOffset - document.getLineStartOffset(lineNum - 1) + 1
            val severity = when {
                info.severity >= HighlightSeverity.ERROR -> "ERROR"
                info.severity >= HighlightSeverity.WARNING -> "WARNING"
                else -> "INFO"
            }
            sb.appendLine("$severity $path:$lineNum:$col ${info.description ?: ""}")
            true
        }
    }

    private fun resolveSymbol(args: Map<String, String>): McpToolRouter.ToolResult {
        val filePath = args["file"] ?: return McpToolRouter.ToolResult("Missing 'file' argument", isError = true)
        val line = args["line"]?.toIntOrNull() ?: return McpToolRouter.ToolResult("Missing or invalid 'line' argument", isError = true)
        val column = args["column"]?.toIntOrNull() ?: return McpToolRouter.ToolResult("Missing or invalid 'column' argument", isError = true)

        if (DumbService.isDumb(project)) {
            return McpToolRouter.ToolResult("Indexing in progress, try again shortly.", isError = true)
        }

        val psiFile = PsiUtils.resolvePsiFile(project, filePath)
            ?: return McpToolRouter.ToolResult("File not found: $filePath", isError = true)

        val result = runReadAction {
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@runReadAction "Cannot get document for $filePath"

            val lineIndex = (line - 1).coerceIn(0, document.lineCount - 1)
            val lineStart = document.getLineStartOffset(lineIndex)
            val lineEnd = document.getLineEndOffset(lineIndex)
            val offset = (lineStart + column - 1).coerceIn(lineStart, lineEnd)

            val element = psiFile.findElementAt(offset)
                ?: return@runReadAction "No element found at $filePath:$line:$column"

            val ref = element.reference
            val target = ref?.resolve()

            if (target == null) {
                // Try parent elements for references
                val parent = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
                if (parent != null) {
                    val targetFile = parent.containingFile
                    val targetLine = PsiUtils.getLineNumber(targetFile, parent.textOffset)
                    val targetPath = PsiUtils.getFilePath(targetFile, project)
                    val context = PsiUtils.getSurroundingLines(targetFile, parent.textOffset, 5)
                    return@runReadAction "Definition: $targetPath:$targetLine\n\n$context"
                }
                return@runReadAction "Cannot resolve symbol at $filePath:$line:$column"
            }

            val targetFile = target.containingFile
            val targetLine = PsiUtils.getLineNumber(targetFile, target.textOffset)
            val targetPath = PsiUtils.getFilePath(targetFile, project)
            val context = PsiUtils.getSurroundingLines(targetFile, target.textOffset, 5)
            "Definition: $targetPath:$targetLine\n\n$context"
        }

        return McpToolRouter.ToolResult(result)
    }
}
