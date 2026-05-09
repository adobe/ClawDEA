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
package com.adobe.clawdea.knowledge.workspace

/**
 * Renders the per-project SIBLINGS.md text from a WorkspaceManifest.
 * Annotates the current repo (if known) so Claude does not redundantly
 * call read_sibling_wiki against the local repo it is already in.
 *
 * Group filtering is the caller's responsibility — pass an already-filtered
 * manifest. SiblingsSource handles this.
 */
object SiblingsRenderer {

    fun render(
        manifest: WorkspaceManifest,
        currentRepoKey: String?,
        currentGroupName: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# Workspace: ${manifest.name.ifBlank { "(unnamed)" }}")
        sb.appendLine()

        if (manifest.repos.isEmpty()) {
            sb.appendLine("_(no sibling repos in the manifest yet — edit `.clawdea-workspace.md` to add them)_")
            return sb.toString().trimEnd() + "\n"
        }

        sb.appendLine("Sibling repos available via `read_sibling_wiki(repo, page)` and `read_sibling_repo_state(repo)`:")
        sb.appendLine()

        val viaPath = computeViaPaths(manifest, currentGroupName)

        for (repo in manifest.repos) {
            val keyLabel = if (repo.key == currentRepoKey) "**${repo.key}** (this repo)" else "**${repo.key}**"
            val jira = if (repo.jiraPrefixes.isEmpty()) "" else " _jira: ${repo.jiraPrefixes.joinToString(", ")}_"
            val via = viaPath[repo.key]?.let { " _(via $it)_" } ?: ""
            sb.appendLine("- $keyLabel — ${repo.role}$jira$via")
            val dir = manifest.discoveredAt
            if (dir != null) {
                val resolved = repo.resolvedPath(dir)
                sb.appendLine("  - Wiki: `$resolved/.claude/wiki/`")
            }
        }

        return sb.toString().trimEnd() + "\n"
    }

    private fun computeViaPaths(manifest: WorkspaceManifest, currentGroupName: String?): Map<String, String> {
        if (currentGroupName == null) return emptyMap()
        val byName = manifest.groups.associateBy { it.name }
        val pathToGroup = mutableMapOf<String, String>()
        val visited = mutableSetOf(currentGroupName)
        fun walk(name: String, path: String) {
            if (!visited.add(name)) return
            pathToGroup[name] = path
            val g = byName[name] ?: return
            for (dep in g.dependsOn) {
                if (dep in byName) walk(dep, "$path → $dep")
            }
        }
        val current = byName[currentGroupName] ?: return emptyMap()
        for (dep in current.dependsOn) {
            if (dep in byName) walk(dep, "$currentGroupName → $dep")
        }
        val out = mutableMapOf<String, String>()
        for ((groupName, path) in pathToGroup) {
            byName[groupName]?.repos?.forEach { repo -> out[repo.key] = path }
        }
        return out
    }
}
