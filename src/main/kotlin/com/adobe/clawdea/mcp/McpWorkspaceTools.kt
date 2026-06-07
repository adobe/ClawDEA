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

import com.adobe.clawdea.knowledge.workspace.WorkspaceDiscovery
import com.adobe.clawdea.knowledge.workspace.WorkspaceManifest
import com.adobe.clawdea.knowledge.workspace.WorkspaceManifestFilter
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class McpWorkspaceTools(private val project: Project) {

    fun registerAll(router: McpToolRouter) {
        router.register(
            name = LIST_TOOL_NAME,
            description = LIST_TOOL_DESCRIPTION,
            properties = emptyList(),
            required = emptyList(),
            handler = ::listWorkspaceRepos,
        )
        router.register(
            name = READ_SIBLING_WIKI_TOOL_NAME,
            description = READ_SIBLING_WIKI_TOOL_DESCRIPTION,
            properties = listOf(
                Triple("repo", "string", "Repo key from the workspace manifest (e.g. 'core')"),
                Triple("page", "string", "Wiki page name without .md (e.g. 'rollout-flow') or 'index'"),
            ),
            required = listOf("repo", "page"),
            handler = ::readSiblingWiki,
        )
        router.register(
            name = READ_SIBLING_REPO_STATE_TOOL_NAME,
            description = READ_SIBLING_REPO_STATE_TOOL_DESCRIPTION,
            properties = listOf(
                Triple("repo", "string", "Repo key from the workspace manifest (e.g. 'core')"),
            ),
            required = listOf("repo"),
            handler = ::readSiblingRepoState,
        )
    }

    private fun listWorkspaceRepos(@Suppress("UNUSED_PARAMETER") args: Map<String, String>): McpToolRouter.ToolResult {
        val state = ClawDEASettings.getInstance().state
        if (!state.enableWorkspace) {
            return McpToolRouter.ToolResult("(workspace mode disabled in settings)")
        }
        val (rawManifest, manifestDir) = locateManifest()
            ?: return McpToolRouter.ToolResult(
                "(no .clawdea-workspace.md found above ${project.basePath} — single-project mode)"
            )
        val manifest = WorkspaceManifestFilter.filterToCurrentGroup(rawManifest, manifestDir, project.basePath?.let { Paths.get(it) })
        val sb = StringBuilder()
        sb.appendLine("Workspace: ${manifest.name.ifBlank { "(unnamed)" }}")
        sb.appendLine("Manifest dir: $manifestDir")
        sb.appendLine()
        if (manifest.repos.isEmpty()) {
            sb.appendLine("(no repos in manifest)")
        } else {
            val currentGroupName = manifest.groups.firstOrNull { group ->
                group.repos.any { repo ->
                    runCatching {
                        repo.resolvedPath(manifestDir).toRealPath() ==
                            project.basePath?.let { Paths.get(it).toRealPath() }
                    }.getOrDefault(false)
                }
            }?.name
            val labels = currentGroupName?.let { labelsForClosure(manifest, it) } ?: emptyMap()
            for ((index, group) in manifest.groups.withIndex()) {
                if (index > 0) sb.appendLine()
                val heading = when (val label = labels[group.name]) {
                    null -> "Group: ${group.name}"
                    "Current group" -> "Current group: ${group.name}"
                    else -> "$label: ${group.name}"
                }
                sb.appendLine(heading)
                for (repo in group.repos) {
                    val resolved = repo.resolvedPath(manifestDir)
                    sb.appendLine("- ${repo.key} → $resolved")
                    sb.appendLine("    role: ${repo.role}")
                    if (repo.jiraPrefixes.isNotEmpty()) {
                        sb.appendLine("    jira: ${repo.jiraPrefixes.joinToString(", ")}")
                    }
                }
            }
        }
        return McpToolRouter.ToolResult(sb.toString().trimEnd())
    }

    private fun readSiblingWiki(args: Map<String, String>): McpToolRouter.ToolResult {
        val repoKey = args["repo"] ?: return McpToolRouter.ToolResult("Missing 'repo' argument", isError = true)
        val pageName = args["page"] ?: return McpToolRouter.ToolResult("Missing 'page' argument", isError = true)
        if (pageName.contains("..") || pageName.contains('/') || pageName.contains('\\')) {
            return McpToolRouter.ToolResult("Invalid page name '$pageName'", isError = true)
        }

        val (rawManifest, manifestDir) = locateManifest()
            ?: return McpToolRouter.ToolResult("(no workspace manifest found above ${project.basePath})")
        val manifest = WorkspaceManifestFilter.filterToCurrentGroup(rawManifest, manifestDir, project.basePath?.let { Paths.get(it) })

        val repo = manifest.repos.firstOrNull { it.key == repoKey }
            ?: return McpToolRouter.ToolResult("(no repo '$repoKey' in workspace manifest)")

        val state = ClawDEASettings.getInstance().state
        val repoRoot = repo.resolvedPath(manifestDir)
        // Honor the sibling repo's own .clawdea/config.json: a teammate may have
        // relocated that repo's wiki (team mode) to e.g. docs/llm-wiki/, which
        // differs from this project's wiki location. Falls back to the default
        // .clawdea/<wikiSubdir> when the sibling has no config.
        val wikiDir = com.adobe.clawdea.knowledge.wiki.WikiLocator
            .resolveForRepo(repoRoot, state.wikiSubdir).wikiDir

        fun pageIn(dir: Path): Path =
            if (pageName == "index" || pageName == "index.md") {
                dir.resolve("index.md")
            } else {
                val safe = if (pageName.endsWith(".md")) pageName else "$pageName.md"
                dir.resolve("concepts").resolve(safe)
            }

        // Prefer the resolved (default `.clawdea/wiki` or team-mode) location;
        // fall back to the legacy `.claude/<wikiSubdir>` for siblings that
        // haven't been opened (and thus migrated) in the IDE yet.
        val resolvedPage = pageIn(wikiDir)
        val legacyPage = pageIn(repoRoot.resolve(state.claudeDirName).resolve(state.wikiSubdir))
        val pageFile = when {
            Files.isRegularFile(resolvedPage) -> resolvedPage
            Files.isRegularFile(legacyPage) -> legacyPage
            else -> resolvedPage
        }

        if (!Files.isRegularFile(pageFile)) {
            return McpToolRouter.ToolResult("(no wiki page '$pageName' in repo '$repoKey' at $pageFile)")
        }
        val content = try {
            Files.readString(pageFile)
        } catch (e: Throwable) {
            return McpToolRouter.ToolResult("Failed to read $pageFile: ${e.message}", isError = true)
        }
        return McpToolRouter.ToolResult(content)
    }

    private fun readSiblingRepoState(args: Map<String, String>): McpToolRouter.ToolResult {
        val repoKey = args["repo"] ?: return McpToolRouter.ToolResult("Missing 'repo' argument", isError = true)
        val (rawManifest, manifestDir) = locateManifest()
            ?: return McpToolRouter.ToolResult("(no workspace manifest found above ${project.basePath})")
        val manifest = WorkspaceManifestFilter.filterToCurrentGroup(rawManifest, manifestDir, project.basePath?.let { Paths.get(it) })

        val repo = manifest.repos.firstOrNull { it.key == repoKey }
            ?: return McpToolRouter.ToolResult("(no repo '$repoKey' in workspace manifest)")

        val state = ClawDEASettings.getInstance().state
        val repoRoot = repo.resolvedPath(manifestDir)
        // Preferred new location is the sibling's .clawdea/; fall back to the
        // legacy .claude/<claudeDirName> location for siblings that haven't been
        // opened (and thus migrated) in the IDE yet.
        val clawdeaFile = repoRoot.resolve(com.adobe.clawdea.CLAWDEA_DIR).resolve("REPO_STATE.md")
        val legacyFile = repoRoot.resolve(state.claudeDirName).resolve("REPO_STATE.md")
        val stateFile = when {
            Files.isRegularFile(clawdeaFile) -> clawdeaFile
            Files.isRegularFile(legacyFile) -> legacyFile
            else -> clawdeaFile
        }
        if (!Files.isRegularFile(stateFile)) {
            return McpToolRouter.ToolResult(
                "(no REPO_STATE.md in repo '$repoKey' at $stateFile — sibling may not be onboarded yet)"
            )
        }
        val content = try {
            Files.readString(stateFile)
        } catch (e: Throwable) {
            return McpToolRouter.ToolResult("Failed to read $stateFile: ${e.message}", isError = true)
        }
        return McpToolRouter.ToolResult(content)
    }

    /**
     * Centralizes the discovery + parse pair. Returns null when the
     * project is not in a workspace or when the toggle is off. Used by
     * all three handlers (list_workspace_repos, read_sibling_wiki,
     * read_sibling_repo_state).
     */
    private fun locateManifest(): Pair<WorkspaceManifest, Path>? {
        val state = ClawDEASettings.getInstance().state
        if (!state.enableWorkspace) return null
        val basePath = project.basePath ?: return null
        val manifestPath = WorkspaceDiscovery.discover(Paths.get(basePath), state.workspaceManifestName)
            ?: return null
        val manifest = WorkspaceDiscovery.parseManifest(manifestPath) ?: return null
        return manifest to manifestPath.parent
    }

    companion object {
        internal fun labelsForClosure(
            manifest: WorkspaceManifest,
            currentGroupName: String,
        ): Map<String, String> {
            val byName = manifest.groups.associateBy { it.name }
            val current = byName[currentGroupName] ?: return emptyMap()
            val labels = mutableMapOf(currentGroupName to "Current group")
            val directDeps = current.dependsOn.toSet()
            val visited = mutableSetOf(currentGroupName)
            fun walk(name: String, viaParent: String) {
                if (!visited.add(name)) return
                val g = byName[name] ?: return
                labels[name] = if (name in directDeps) "Depends on (transitive)"
                else "Depends on (via $viaParent)"
                for (dep in g.dependsOn) {
                    if (dep in byName) walk(dep, name)
                }
            }
            for (dep in current.dependsOn) {
                if (dep in byName) walk(dep, currentGroupName)
            }
            return labels
        }

        const val LIST_TOOL_NAME = "list_workspace_repos"
        const val LIST_TOOL_DESCRIPTION =
            "List sibling repos defined in the workspace manifest (.clawdea-workspace.md). " +
            "Returns each repo's key, resolved filesystem path, role, and jira prefixes. " +
            "Returns a single line if the project is not in a workspace."
        const val READ_SIBLING_WIKI_TOOL_NAME = "read_sibling_wiki"
        const val READ_SIBLING_WIKI_TOOL_DESCRIPTION =
            "Read a wiki page from a sibling repo defined in the workspace manifest. " +
            "Use list_workspace_repos to see available repo keys. The 'page' argument " +
            "matches read_wiki_page semantics ('index' for the table of contents, " +
            "or a concept slug like 'rollout-flow')."
        const val READ_SIBLING_REPO_STATE_TOOL_NAME = "read_sibling_repo_state"
        const val READ_SIBLING_REPO_STATE_TOOL_DESCRIPTION =
            "Read REPO_STATE.md from a sibling repo defined in the workspace manifest. " +
            "Use this for cross-project orientation: branch, recent commits, hot files. " +
            "Returns informative text when the sibling has not been onboarded to the " +
            "ClawDEA knowledge layer."
    }
}
