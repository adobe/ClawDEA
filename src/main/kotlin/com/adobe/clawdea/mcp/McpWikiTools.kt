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

import com.adobe.clawdea.knowledge.drift.DriftDetectionService
import com.adobe.clawdea.knowledge.wiki.WikiPageReader
import com.adobe.clawdea.knowledge.wiki.WikiPath
import com.adobe.clawdea.knowledge.wiki.WikiSearcher
import com.adobe.clawdea.settings.ClawDEASettings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.project.Project
import java.nio.file.Paths

class McpWikiTools(private val project: Project) {

    fun registerAll(router: McpToolRouter) {
        router.register(
            name = READ_TOOL_NAME,
            description = READ_TOOL_DESCRIPTION,
            properties = listOf(
                Triple("name", "string", "Page name without .md (e.g. 'rollout-flow' or 'index')"),
                Triple("kind", "string", "Optional: 'concept' (default), 'source', or 'index'"),
            ),
            required = listOf("name"),
            handler = ::readWikiPage,
        )
        router.register(
            name = SEARCH_TOOL_NAME,
            description = SEARCH_TOOL_DESCRIPTION,
            properties = listOf(
                Triple("query", "string", "Case-insensitive substring to search for in wiki pages"),
                Triple("pathTokens", "array:string", "Optional path tokens from diff context (e.g. policies, clientlibs, jcr_root) to match against page titles and headings"),
            ),
            required = listOf("query"),
            handler = ::searchWiki,
        )
    }

    private fun wikiPath(): WikiPath? {
        val basePath = project.basePath ?: return null
        val state = ClawDEASettings.getInstance().state
        return WikiPath(Paths.get(basePath, state.claudeDirName, state.wikiSubdir))
    }

    private fun readWikiPage(args: Map<String, String>): McpToolRouter.ToolResult {
        val name = args["name"] ?: return McpToolRouter.ToolResult("Missing 'name' argument", isError = true)
        val kind = (args["kind"] ?: "concept").lowercase()
        val wp = wikiPath() ?: return McpToolRouter.ToolResult("No project basePath", isError = true)
        val reader = WikiPageReader(wp)
        val content = when (kind) {
            "concept" -> reader.readConcept(name)
            "source" -> reader.readSource(name)
            "index" -> reader.readIndex()
            else -> return McpToolRouter.ToolResult("Unknown kind '$kind' (expected concept|source|index)", isError = true)
        }
        return if (content == null) {
            McpToolRouter.ToolResult("(no $kind page named '$name')")
        } else {
            McpToolRouter.ToolResult(content)
        }
    }

    private fun searchWiki(args: Map<String, String>): McpToolRouter.ToolResult {
        val query = args["query"] ?: return McpToolRouter.ToolResult("Missing 'query' argument", isError = true)
        val pathTokens = parsePathTokens(args["pathTokens"])
        val wp = wikiPath() ?: return McpToolRouter.ToolResult("No project basePath", isError = true)
        val hits = WikiSearcher(wp).search(query, pathTokens)

        maybeRecordProbeMiss(query, pathTokens, hits.size, args["taskContext"])

        if (hits.isEmpty()) return McpToolRouter.ToolResult("(no matches for '$query')")
        val sb = StringBuilder()
        for (hit in hits.take(20)) {
            sb.appendLine("--- ${hit.relativePath}:${hit.firstHitLine} (${hit.matchCount} match${if (hit.matchCount > 1) "es" else ""}) ---")
            sb.appendLine(hit.snippet)
            sb.appendLine()
        }
        return McpToolRouter.ToolResult(sb.toString().trimEnd())
    }

    private fun parsePathTokens(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            GSON.fromJson<List<String>>(raw, STRING_LIST_TYPE)?.filter { it.isNotBlank() } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun maybeRecordProbeMiss(query: String, pathTokens: List<String>, hitCount: Int, taskContext: String?) {
        val queryTokenCount = query.split("\\s+".toRegex()).size
        val isNonTrivial = queryTokenCount >= 2 || query.length >= 8
        if (!isNonTrivial) return
        if (hitCount >= 2) return
        val contextHash = taskContext?.hashCode()?.toUInt()?.toString(16)
            ?: project.basePath?.hashCode()?.toUInt()?.toString(16)
            ?: "unknown"
        project.getService(DriftDetectionService::class.java)
            .recordProbeMiss(query, pathTokens, hitCount, contextHash)
    }

    companion object {
        private val GSON = Gson()
        private val STRING_LIST_TYPE = object : TypeToken<List<String>>() {}.type

        const val READ_TOOL_NAME = "read_wiki_page"
        const val READ_TOOL_DESCRIPTION =
            "Read a wiki page (concept, source, or index) from .claude/wiki/. Use to access " +
            "synthesized project knowledge that complements CLAUDE.md."
        const val SEARCH_TOOL_NAME = "search_wiki"
        const val SEARCH_TOOL_DESCRIPTION =
            "Search the project wiki at .claude/wiki/ for a substring query. Returns ranked " +
            "snippets with file path and line number; use read_wiki_page for full content."
    }
}
