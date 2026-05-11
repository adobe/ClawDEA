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

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile

/**
 * MCP tool handler for literal/regex content search across the project.
 *
 * Fills the gap left by the symbol-based tools (find_files, find_usages, etc.):
 * those resolve PSI symbols, but agents frequently need to search for raw
 * strings that have no PSI representation — CLI flag literals, error
 * messages, log strings, config keys. Without this tool, agents fall back to
 * Bash grep, which the system prompt forbids without alternative.
 *
 * Backed by [ProjectFileIndex.iterateContent] so it respects the IDE's
 * content-root configuration (excludes build dirs, node_modules, .git, etc.)
 * automatically.
 */
class McpSearchTextTool(private val project: Project) {

    fun registerAll(router: McpToolRouter) {
        router.register(
            name = "search_text",
            description = "Search project source content for a literal substring (or regex). " +
                "Returns up to $MAX_MATCHES matches with file path, line number, and the matching line. " +
                "Respects project scope (excludes build/output dirs and binary files). " +
                "Use this for literal strings (CLI flags, error messages, config keys, log lines) that " +
                "have no PSI symbol — for class/method/field navigation, prefer find_files/find_usages/find_callers.",
            properties = listOf(
                Triple("query", "string", "Substring to search for. Treated literally unless regex=\"true\". Minimum 2 characters."),
                Triple("regex", "string", "Optional: \"true\" to interpret query as a Java regular expression. Default \"false\"."),
                Triple("glob", "string", "Optional file-name glob filter (e.g. \"*.kt\", \"*.java\"). Applied to file names only. Default: all source files."),
                Triple("case_sensitive", "string", "Optional: \"true\" or \"false\". Default \"false\"."),
            ),
            required = listOf("query"),
            handler = ::search,
        )
    }

    private fun search(args: Map<String, String>): McpToolRouter.ToolResult {
        val query = args["query"] ?: return McpToolRouter.ToolResult("Missing 'query' argument", isError = true)
        if (query.length < 2) return McpToolRouter.ToolResult("'query' must be at least 2 characters", isError = true)

        val isRegex = args["regex"]?.equals("true", ignoreCase = true) == true
        val caseSensitive = args["case_sensitive"]?.equals("true", ignoreCase = true) == true
        val glob = args["glob"]?.takeIf { it.isNotBlank() }

        val pattern: Regex = try {
            val src = if (isRegex) query else Regex.escape(query)
            val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(src, opts)
        } catch (e: Exception) {
            return McpToolRouter.ToolResult("Invalid regex: ${e.message}", isError = true)
        }

        val globRegex: Regex? = glob?.let { compileGlob(it) }

        val matches = mutableListOf<String>()
        val basePath = project.basePath
            ?: return McpToolRouter.ToolResult("Project has no base path", isError = true)

        runReadAction {
            val index = ProjectFileIndex.getInstance(project)
            index.iterateContent { vf ->
                if (matches.size >= MAX_MATCHES) return@iterateContent false
                if (!shouldScan(vf, globRegex)) return@iterateContent true
                val text = try {
                    String(vf.contentsToByteArray(), vf.charset)
                } catch (_: Exception) {
                    return@iterateContent true
                }
                scanText(text, pattern, relativize(vf.path, basePath), matches)
                true
            }
        }

        if (matches.isEmpty()) {
            val scope = if (glob != null) " in files matching '$glob'" else ""
            return McpToolRouter.ToolResult("No matches for '$query'$scope.")
        }

        val sb = StringBuilder()
        val symbolHint = symbolMisuseHint(query)
        if (symbolHint != null) sb.appendLine(symbolHint).appendLine()
        for (m in matches.take(MAX_MATCHES)) sb.append(m)
        if (matches.size >= MAX_MATCHES) {
            sb.appendLine("... result capped at $MAX_MATCHES matches; refine the query or pass a 'glob' filter.")
        }
        return McpToolRouter.ToolResult(sb.toString())
    }

    private fun shouldScan(vf: VirtualFile, globRegex: Regex?): Boolean {
        if (vf.isDirectory) return false
        if (vf.fileType.isBinary) return false
        if (vf.length > MAX_FILE_SIZE) return false
        if (globRegex != null && !globRegex.matches(vf.name)) return false
        return true
    }

    companion object {
        internal const val MAX_MATCHES = 30
        internal const val MAX_FILE_SIZE = 1L * 1024 * 1024 // 1 MB
        private const val MAX_LINE_LEN = 240

        internal fun compileGlob(glob: String): Regex {
            val sb = StringBuilder("^")
            for (c in glob) {
                when (c) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    '.', '\\', '+', '(', ')', '[', ']', '{', '}', '|', '^', '$' -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
            }
            sb.append('$')
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }

        /**
         * Scan [text] line-by-line for [pattern], appending matches to [out] in
         * `--- relPath:line ---\n<line>\n` format. Stops once [out] hits [MAX_MATCHES].
         * Long lines are truncated for readability — the agent doesn't need every
         * char, just enough to recognize the match site.
         */
        internal fun scanText(
            text: String,
            pattern: Regex,
            relPath: String,
            out: MutableList<String>,
        ) {
            var lineStart = 0
            var lineNumber = 1
            var i = 0
            val len = text.length
            while (i <= len) {
                val isEnd = i == len
                if (isEnd || text[i] == '\n') {
                    val rawLine = text.substring(lineStart, i)
                    if (pattern.containsMatchIn(rawLine)) {
                        val display = rawLine.trim().let {
                            if (it.length > MAX_LINE_LEN) it.substring(0, MAX_LINE_LEN) + "…" else it
                        }
                        out.add("--- $relPath:$lineNumber ---\n$display\n")
                        if (out.size >= MAX_MATCHES) return
                    }
                    lineNumber++
                    lineStart = i + 1
                }
                i++
            }
        }

        internal fun relativize(fullPath: String, basePath: String): String =
            if (fullPath.startsWith(basePath)) fullPath.removePrefix(basePath).removePrefix("/") else fullPath

        private val SYMBOL_PATTERN = Regex(
            """^(class|fun|interface|object|enum|val|var|def|function|const)\s+\w+$""" +
            """|^[A-Z][a-z][a-zA-Z0-9]*$""" +
            """|^[a-z][a-zA-Z0-9]*[A-Z][a-zA-Z0-9]*$"""
        )

        internal fun symbolMisuseHint(query: String): String? {
            if (!SYMBOL_PATTERN.containsMatchIn(query.trim())) return null
            return "[NOTE: This query looks like a code symbol. For symbol navigation, " +
                "use find_usages (references), find_callers (call sites), find_implementations, " +
                "or find_files (by filename) instead of search_text. " +
                "search_text is for literal strings like error messages, config keys, or log lines.]"
        }
    }
}
