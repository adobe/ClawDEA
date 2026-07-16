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

import com.adobe.clawdea.language.LanguageSupportRegistry
import com.adobe.clawdea.util.ignoringStaleIndex
import com.adobe.clawdea.util.runReadAction

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch

/**
 * MCP tool handlers for index-based queries:
 * find_callers, find_implementations, find_usages, find_supertypes, find_related_types.
 */
class McpIndexTools(private val project: Project) {

    private val log = Logger.getInstance(McpIndexTools::class.java)

    fun registerAll(router: McpToolRouter) {
        router.register(
            name = "find_callers",
            description = "Find methods/functions that call the method at the given file and line. Returns call sites with file paths, line numbers, and surrounding code.",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative file path"),
                Triple("line", "string", "1-based line number where the method is defined"),
            ),
            required = listOf("file", "line"),
            handler = ::findCallers,
        )
        router.register(
            name = "find_implementations",
            description = "Find classes that implement or extend the interface/abstract class at the given file and line. Returns class names and method signatures.",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative file path"),
                Triple("line", "string", "1-based line number where the class/interface is defined"),
            ),
            required = listOf("file", "line"),
            handler = ::findImplementations,
        )
        router.register(
            name = "find_usages",
            description = "Find all references to the symbol at the given file and line. Returns usage sites with file paths, line numbers, and surrounding code.",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative file path"),
                Triple("line", "string", "1-based line number of the symbol"),
            ),
            required = listOf("file", "line"),
            handler = ::findUsages,
        )
        router.register(
            name = "find_supertypes",
            description = "Find the type hierarchy (superclasses and interfaces) for the class at the given file and line. Returns parent type names and method signatures.",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative file path"),
                Triple("line", "string", "1-based line number where the class is defined"),
            ),
            required = listOf("file", "line"),
            handler = ::findSupertypes,
        )
        router.register(
            name = "find_related_types",
            description = "Find signatures of project-scope types imported by the given file. Returns type names, method signatures, and field signatures.",
            properties = listOf(
                Triple("file", "string", "Absolute or project-relative file path"),
            ),
            required = listOf("file"),
            handler = ::findRelatedTypes,
        )
        router.register(
            name = "find_files",
            description = "Search for files in the project by name pattern. Uses IntelliJ's file index — faster than find/ls and respects project scope (excludes build dirs, node_modules, etc.). Supports substring matching and glob patterns.",
            properties = listOf(
                Triple("pattern", "string", "File name pattern to search for (e.g. 'ChatPanel', '*.kt', 'McpServer.kt')"),
            ),
            required = listOf("pattern"),
            handler = ::findFiles,
        )
        router.register(
            name = "find_symbol",
            description = "Find definitions of a class, method, or field by name. Returns file path, line number, and surrounding code for each match. Use this as the FIRST tool when you know a symbol name but not its location — it resolves names to file+line so you can then use find_usages/find_callers.",
            properties = listOf(
                Triple("name", "string", "Symbol name to find (e.g. 'ChatPanel', 'syncStreamingUi', 'MAX_MATCHES'). Case-sensitive."),
                Triple("kind", "string", "Optional filter: 'class', 'method', or 'field'. Default: searches all kinds."),
            ),
            required = listOf("name"),
            handler = ::findSymbol,
        )
    }

    private fun findCallers(args: Map<String, String>): McpToolRouter.ToolResult {
        val file = args["file"] ?: return McpToolRouter.ToolResult("Missing 'file' argument", isError = true)
        val line = args["line"]?.toIntOrNull() ?: return McpToolRouter.ToolResult("Missing or invalid 'line' argument", isError = true)

        if (DumbService.isDumb(project)) return dumbResult()

        val psiFile = PsiUtils.resolvePsiFile(project, file) ?: return fileNotFound(file)
        val method = PsiUtils.findMethodAtLine(psiFile, line)
            ?: return McpToolRouter.ToolResult("No method found at line $line of ${psiFile.name}", isError = true)

        val results = runReadAction {
            val scope = GlobalSearchScope.projectScope(project)
            val refs = ReferencesSearch.search(method, scope).findAll()
            val sb = StringBuilder()
            for (ref in refs.take(10)) {
                val element = ref.element
                val refFile = element.containingFile ?: continue
                val lineNum = PsiUtils.getLineNumber(refFile, element.textOffset)
                val path = PsiUtils.getFilePath(refFile, project)
                val context = PsiUtils.getSurroundingLines(refFile, element.textOffset, 3)
                sb.appendLine("--- $path:$lineNum ---")
                sb.appendLine(context)
                sb.appendLine()
            }
            if (sb.isEmpty()) "No callers found for ${method.name}" else sb.toString()
        }

        return McpToolRouter.ToolResult(results)
    }

    private fun findImplementations(args: Map<String, String>): McpToolRouter.ToolResult {
        val file = args["file"] ?: return McpToolRouter.ToolResult("Missing 'file' argument", isError = true)
        val line = args["line"]?.toIntOrNull() ?: return McpToolRouter.ToolResult("Missing or invalid 'line' argument", isError = true)

        if (DumbService.isDumb(project)) return dumbResult()

        val psiFile = PsiUtils.resolvePsiFile(project, file) ?: return fileNotFound(file)
        val psiClass = PsiUtils.findClassAtLine(psiFile, line)
            ?: return McpToolRouter.ToolResult("No class or interface found at line $line of ${psiFile.name}", isError = true)

        val results = runReadAction {
            val scope = GlobalSearchScope.projectScope(project)
            val inheritors = ClassInheritorsSearch.search(psiClass, scope, false).findAll()
            val sb = StringBuilder()
            for (impl in inheritors.take(10)) {
                val path = impl.containingFile?.let { PsiUtils.getFilePath(it, project) } ?: "unknown"
                sb.appendLine("--- ${impl.name} ($path) ---")
                sb.appendLine("class ${impl.name}")
                for (m in impl.methods.take(10)) {
                    sb.appendLine("  ${PsiUtils.formatMethodSignature(m)}")
                }
                sb.appendLine()
            }
            if (sb.isEmpty()) "No implementations found for ${psiClass.name}" else sb.toString()
        }

        return McpToolRouter.ToolResult(results)
    }

    private fun findUsages(args: Map<String, String>): McpToolRouter.ToolResult {
        val file = args["file"] ?: return McpToolRouter.ToolResult("Missing 'file' argument", isError = true)
        val line = args["line"]?.toIntOrNull() ?: return McpToolRouter.ToolResult("Missing or invalid 'line' argument", isError = true)

        if (DumbService.isDumb(project)) return dumbResult()

        val psiFile = PsiUtils.resolvePsiFile(project, file) ?: return fileNotFound(file)
        val element = runReadAction {
            val el = PsiUtils.elementAtLine(psiFile, line)
            el?.reference?.resolve() ?: el?.let {
                com.intellij.psi.util.PsiTreeUtil.getParentOfType(it, PsiNamedElement::class.java)
            }
        } ?: return McpToolRouter.ToolResult("No symbol found at line $line of ${psiFile.name}", isError = true)

        val name = runReadAction {
            (element as? PsiNamedElement)?.name ?: "symbol"
        }

        val results = runReadAction {
            val scope = GlobalSearchScope.projectScope(project)
            val refs = ReferencesSearch.search(element, scope).findAll()
            val sb = StringBuilder()
            for (ref in refs.take(15)) {
                val el = ref.element
                val refFile = el.containingFile ?: continue
                val lineNum = PsiUtils.getLineNumber(refFile, el.textOffset)
                val path = PsiUtils.getFilePath(refFile, project)
                val context = PsiUtils.getSurroundingLines(refFile, el.textOffset, 2)
                sb.appendLine("--- $path:$lineNum ---")
                sb.appendLine(context)
                sb.appendLine()
            }
            if (sb.isEmpty()) "No usages found for $name" else sb.toString()
        }

        return McpToolRouter.ToolResult(results)
    }

    private fun findSupertypes(args: Map<String, String>): McpToolRouter.ToolResult {
        val file = args["file"] ?: return McpToolRouter.ToolResult("Missing 'file' argument", isError = true)
        val line = args["line"]?.toIntOrNull() ?: return McpToolRouter.ToolResult("Missing or invalid 'line' argument", isError = true)

        if (DumbService.isDumb(project)) return dumbResult()

        val psiFile = PsiUtils.resolvePsiFile(project, file) ?: return fileNotFound(file)
        val psiClass = PsiUtils.findClassAtLine(psiFile, line)
            ?: return McpToolRouter.ToolResult("No class found at line $line of ${psiFile.name}", isError = true)

        val results = runReadAction {
            val sb = StringBuilder()
            for (superType in psiClass.supers) {
                if (superType.qualifiedName in setOf("java.lang.Object", "kotlin.Any")) continue
                val kind = if (superType.isInterface) "interface" else "class"
                sb.appendLine("--- $kind ${superType.name} ---")
                for (m in superType.methods) {
                    sb.appendLine("  ${PsiUtils.formatMethodSignature(m)}")
                }
                sb.appendLine()
            }
            if (sb.isEmpty()) "No supertypes found for ${psiClass.name}" else sb.toString()
        }

        return McpToolRouter.ToolResult(results)
    }

    private fun findRelatedTypes(args: Map<String, String>): McpToolRouter.ToolResult {
        val file = args["file"] ?: return McpToolRouter.ToolResult("Missing 'file' argument", isError = true)

        if (DumbService.isDumb(project)) return dumbResult()

        val psiFile = PsiUtils.resolvePsiFile(project, file) ?: return fileNotFound(file)

        val results = runReadAction {
            val support = LanguageSupportRegistry.forPsiFile(psiFile)
            if (support == null) {
                log.debug("find_related_types: no LanguageSupport for file=$file lang=${psiFile.language.id}/${psiFile.language.displayName}")
                return@runReadAction "Related types not supported for language: ${psiFile.language.displayName}."
            }
            log.debug("find_related_types: matched LanguageSupport id=${support.id} for file=$file lang=${psiFile.language.id}")
            val scope = GlobalSearchScope.projectScope(project)
            val result = try {
                support.findRelatedTypes(psiFile, project, scope)
            } catch (e: Throwable) {
                log.warn("find_related_types: ${support.id} threw ${e.javaClass.simpleName}: ${e.message}", e)
                return@runReadAction "find_related_types failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            if (result == null) {
                log.debug("find_related_types: ${support.id} returned null (unsupported file shape)")
                "Related types not supported for language: ${support.displayName}."
            } else {
                log.debug("find_related_types: ${support.id} returned ${result.length} chars")
                result
            }
        }

        return McpToolRouter.ToolResult(results)
    }

    private fun findFiles(args: Map<String, String>): McpToolRouter.ToolResult {
        val pattern = args["pattern"] ?: return McpToolRouter.ToolResult("Missing 'pattern' argument", isError = true)

        if (DumbService.isDumb(project)) return dumbResult()

        val results = runReadAction {
            val scope = GlobalSearchScope.projectScope(project)
            val allNames = FilenameIndex.getAllFilenames(project)

            // Match filenames: exact, substring, or glob
            val isGlob = pattern.contains('*') || pattern.contains('?')
            val globRegex = if (isGlob) {
                Regex(pattern.replace(".", "\\.").replace("*", ".*").replace("?", "."))
            } else null

            val matched = mutableListOf<String>()
            for (name in allNames) {
                val matches = if (isGlob) {
                    globRegex!!.matches(name)
                } else {
                    name.contains(pattern, ignoreCase = true)
                }
                if (!matches) continue

                val files = FilenameIndex.getVirtualFilesByName(name, scope)
                for (vf in files) {
                    val psiFile = PsiManager.getInstance(project).findFile(vf) ?: continue
                    matched.add(PsiUtils.getFilePath(psiFile, project))
                }
            }

            matched.sort()
            val sb = StringBuilder()
            for (path in matched.take(30)) {
                sb.appendLine(path)
            }
            if (matched.size > 30) {
                sb.appendLine("... and ${matched.size - 30} more files")
            }
            if (sb.isEmpty()) "No files found matching '$pattern'" else sb.toString()
        }

        return McpToolRouter.ToolResult(results)
    }

    private fun findSymbol(args: Map<String, String>): McpToolRouter.ToolResult {
        val name = args["name"] ?: return McpToolRouter.ToolResult("Missing 'name' argument", isError = true)
        val kind = args["kind"]?.lowercase()

        if (DumbService.isDumb(project)) return dumbResult()

        val results = runReadAction {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val sb = StringBuilder()
            var count = 0

            if (kind == null || kind == "class") {
                val classes = cache.getClassesByName(name, scope)
                for (cls in classes) {
                    if (count >= MAX_SYMBOL_RESULTS) break
                    val rendered = ignoringStaleIndex(onSkip = { skipStale("class $name", it) }) {
                        val file = cls.containingFile ?: return@ignoringStaleIndex null
                        val path = PsiUtils.getFilePath(file, project)
                        val offset = cls.textOffset
                        val line = PsiUtils.getLineNumber(file, offset)
                        val context = PsiUtils.getSurroundingLines(file, offset, 2)
                        "--- class $name ($path:$line) ---\n$context\n"
                    } ?: continue
                    sb.appendLine(rendered)
                    count++
                }
            }

            if (kind == null || kind == "method") {
                val methods = cache.getMethodsByName(name, scope)
                for (method in methods) {
                    if (count >= MAX_SYMBOL_RESULTS) break
                    val rendered = ignoringStaleIndex(onSkip = { skipStale("method $name", it) }) {
                        val file = method.containingFile ?: return@ignoringStaleIndex null
                        val path = PsiUtils.getFilePath(file, project)
                        val offset = method.textOffset
                        val line = PsiUtils.getLineNumber(file, offset)
                        val containing = (method.containingClass?.name ?: "")
                        val context = PsiUtils.getSurroundingLines(file, offset, 2)
                        "--- method $containing.$name ($path:$line) ---\n$context\n"
                    } ?: continue
                    sb.appendLine(rendered)
                    count++
                }
            }

            if (kind == null || kind == "field") {
                val fields = cache.getFieldsByName(name, scope)
                for (field in fields) {
                    if (count >= MAX_SYMBOL_RESULTS) break
                    val rendered = ignoringStaleIndex(onSkip = { skipStale("field $name", it) }) {
                        val file = field.containingFile ?: return@ignoringStaleIndex null
                        val path = PsiUtils.getFilePath(file, project)
                        val offset = field.textOffset
                        val line = PsiUtils.getLineNumber(file, offset)
                        val containing = ((field as? PsiMember)?.containingClass?.name ?: "")
                        val context = PsiUtils.getSurroundingLines(file, offset, 2)
                        "--- field $containing.$name ($path:$line) ---\n$context\n"
                    } ?: continue
                    sb.appendLine(rendered)
                    count++
                }
            }

            if (sb.isEmpty()) "No symbol found for '$name'" else sb.toString()
        }

        return McpToolRouter.ToolResult(results)
    }

    private fun dumbResult() = McpToolRouter.ToolResult("Indexing in progress, try again shortly.", isError = true)
    private fun fileNotFound(file: String) = McpToolRouter.ToolResult("File not found: $file", isError = true)

    private fun skipStale(what: String, e: Exception) {
        log.info("find_symbol: skipping $what — stale index entry (${e.javaClass.simpleName}: ${e.message})")
    }

    companion object {
        private const val MAX_SYMBOL_RESULTS = 10
    }
}
