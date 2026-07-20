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
import com.adobe.clawdea.knowledge.wiki.WikiLocator
import com.adobe.clawdea.knowledge.wiki.WikiPageReader
import com.adobe.clawdea.knowledge.wiki.WikiPath
import com.adobe.clawdea.knowledge.wiki.WikiSearcher
import com.adobe.clawdea.knowledge.wiki.WikiSuggestionWriter
import com.adobe.clawdea.settings.ClawDEASettings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.project.Project

class McpWikiTools(private val project: Project) {

    fun registerAll(router: McpToolRouter) {
        val state = ClawDEASettings.getInstance().state
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
            name = RECORD_SUGGESTION_TOOL_NAME,
            description = RECORD_SUGGESTION_TOOL_DESCRIPTION,
            properties = listOf(
                Triple("kind", "string", "One of missingConcept, staleConcept, incompleteConcept"),
                Triple("title", "string", "3-7 word title for the proposed change"),
                Triple("rationale", "string", "1-2 sentence explanation of what was observed"),
                Triple("target_files", "array:string", "Wiki paths the change would touch (each under .claude/wiki/, ending in .md). Pass as a JSON array of strings."),
                Triple("source_page", "string", "Optional: wiki page consulted when the gap was noticed"),
            ),
            required = listOf("kind", "title", "rationale", "target_files"),
            handler = ::recordWikiSuggestion,
        )
        if (!state.enableWikiLibrarian) {
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

        // Cross-provider librarian: when the WIKI role is openai-compatible, the in-chat librarian
        // cannot run as a CLI `--agents` subagent (that runs in the chat process on the chat
        // provider). Expose it as an MCP tool whose handler runs the agentic loop on the WIKI
        // provider and returns the answer. The primer/system-prompt (CliProcess) tells the main
        // agent to call this tool instead of Agent(subagent_type="wiki-librarian").
        if (state.enableWikiLibrarian) {
            val wikiSel = com.adobe.clawdea.provider.RoleSelectionStore(ClawDEASettings.getInstance())
                .get(com.adobe.clawdea.provider.AgentRole.WIKI)
            if (com.adobe.clawdea.knowledge.wiki.chooseLibrarianMode(wikiSel) ==
                com.adobe.clawdea.knowledge.wiki.LibrarianMode.AGENTIC_MCP_TOOL) {
                router.register(
                    name = ASK_LIBRARIAN_TOOL_NAME,
                    description = ASK_LIBRARIAN_TOOL_DESCRIPTION,
                    properties = listOf(
                        Triple("question", "string", "The user's question about this project's design/subsystems/conventions."),
                    ),
                    required = listOf("question"),
                    handler = ::askWikiLibrarian,
                )
            }
        }
    }

    private fun wikiPath(): WikiPath? {
        if (project.basePath == null) return null
        return WikiPath(WikiLocator.getInstance(project).wikiDir())
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

    private fun recordWikiSuggestion(args: Map<String, String>): McpToolRouter.ToolResult {
        if (project.basePath == null) {
            return McpToolRouter.ToolResult("No project basePath", isError = true)
        }
        val wikiDir = WikiLocator.getInstance(project).wikiDir()
        val writer = WikiSuggestionWriter(
            wikiDir = wikiDir,
            projectBase = project.basePath?.let { java.nio.file.Paths.get(it) },
        )
        val kind = args["kind"] ?: return McpToolRouter.ToolResult("Missing 'kind' argument", isError = true)
        val title = args["title"] ?: return McpToolRouter.ToolResult("Missing 'title' argument", isError = true)
        val rationale = args["rationale"] ?: return McpToolRouter.ToolResult("Missing 'rationale' argument", isError = true)
        val rawTargetFiles = args["target_files"]
            ?: return McpToolRouter.ToolResult("Missing 'target_files' argument", isError = true)
        val targetFilesCsv = coerceToCsv(rawTargetFiles)
        val result = writer.record(
            kind = kind,
            title = title,
            rationale = rationale,
            targetFilesCsv = targetFilesCsv,
            sourcePage = args["source_page"],
        )
        return when (result) {
            is WikiSuggestionWriter.Result.Recorded -> {
                // Kick a rescan now so the wiki-author picks up this freshly
                // recorded suggestion immediately instead of waiting for the
                // next periodic tick. Without this the page can sit unwritten
                // for a full scan interval (or indefinitely if the periodic
                // loop is wedged on a prior rescan).
                if (result.isNew) {
                    project.getService(DriftDetectionService::class.java).requestRescan()
                }
                McpToolRouter.ToolResult("""{"status":"recorded","signature":"${result.signature}","isNew":${result.isNew}}""")
            }
            is WikiSuggestionWriter.Result.Dismissed ->
                McpToolRouter.ToolResult("""{"status":"dismissed","signature":"${result.signature}"}""")
            is WikiSuggestionWriter.Result.Invalid ->
                McpToolRouter.ToolResult(result.reason, isError = true)
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

    private fun coerceToCsv(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            return try {
                GSON.fromJson<List<String>>(trimmed, STRING_LIST_TYPE)
                    ?.filter { it.isNotBlank() }
                    ?.joinToString(",")
                    ?: trimmed
            } catch (_: Exception) {
                trimmed
            }
        }
        return trimmed
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

    private fun askWikiLibrarian(args: Map<String, String>): McpToolRouter.ToolResult {
        val question = args["question"].orEmpty()
        if (question.isBlank()) {
            return McpToolRouter.ToolResult("missing required parameter: question", isError = true)
        }
        val settings = ClawDEASettings.getInstance()
        val wikiSel = com.adobe.clawdea.provider.RoleSelectionStore(settings)
            .get(com.adobe.clawdea.provider.AgentRole.WIKI)
        val profileId = wikiSel.profileId ?: ""
        val profileStore = com.adobe.clawdea.provider.openai.profile.ProfileStore(settings)
        val resolved = profileStore.resolve(profileId, System.getenv())
            ?: return McpToolRouter.ToolResult(
                "OpenAI-compatible profile '$profileId' not configured", isError = true)
        val credential = com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore().get(profileId)
        if (credential.isBlank()) {
            return McpToolRouter.ToolResult(
                "Credential not configured for profile '$profileId'", isError = true)
        }
        val capability = com.adobe.clawdea.provider.openai.catalog.ModelCapabilityResolver.resolve(
            modelId = wikiSel.modelId,
            endpointCapability = null,
            profileRules = resolved.profile.modelRules,
            userOverride = null,
        )
        val mcpDefs = com.adobe.clawdea.mcp.McpServer.getInstance(project).toolDefinitions()
        val approvalGate = com.adobe.clawdea.provider.openai.tools.SharedToolApprovalGate(
            toolApprovalMode = { settings.state.toolApprovalMode },
            policy = { null },
            route = { toolName, inputJson, toolUseId ->
                com.adobe.clawdea.chat.permission.PermissionRouterRegistry(project)
                    .route(toolName, inputJson, toolUseId)
            },
            promptTimeoutMs = 300_000,
        )
        val client = com.adobe.clawdea.cli.backend.HttpAgentClient(
            com.adobe.clawdea.provider.openai.client.OpenAiCompatibleClient(), resolved, credential)
        val executor = com.adobe.clawdea.cli.backend.defaultExecutor(
            project, mcpDefs, approvalGate, { settings.state.autoAcceptEdits })
        val tools = com.adobe.clawdea.knowledge.wiki.filterLibrarianTools(
            com.adobe.clawdea.cli.backend.agentToolDefinitions(mcpDefs))
        val systemPrompt = try {
            com.adobe.clawdea.knowledge.wiki.WikiAgentsArg.librarianPromptBody()
        } catch (e: Throwable) { null }
        val librarian = com.adobe.clawdea.knowledge.wiki.AgenticLibrarian(
            client = client, executor = executor, tools = tools, modelId = wikiSel.modelId,
            systemPrompt = systemPrompt, capability = capability, streaming = resolved.profile.streaming)
        val answer = kotlinx.coroutines.runBlocking { librarian.ask(question) }
        return McpToolRouter.ToolResult(answer.text, isError = answer.isError)
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
        const val RECORD_SUGGESTION_TOOL_NAME = "record_wiki_suggestion"
        const val RECORD_SUGGESTION_TOOL_DESCRIPTION =
            "Record a proposed wiki improvement (missingConcept | staleConcept | " +
            "incompleteConcept) for the user to review at wiki refresh time. Use sparingly — " +
            "one per distinct gap. Not surfaced to the main chat; only the wiki-librarian " +
            "subagent's allowlist contains this tool. Returns a short ack with the recorded " +
            "signature."
        const val ASK_LIBRARIAN_TOOL_NAME = "ask_wiki_librarian"
        const val ASK_LIBRARIAN_TOOL_DESCRIPTION =
            "Ask this project's wiki librarian a question about its design, subsystems, or " +
                "conventions. Returns a synthesized answer with page citations. Use this as your " +
                "FIRST call for any \"how does X work\" / \"where is Y\" / change-safety question."
    }
}
