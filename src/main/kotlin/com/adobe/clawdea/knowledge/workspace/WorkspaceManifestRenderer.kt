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
 * Renders a [WorkspaceManifest] as markdown text and validates
 * append-only manifest mutations.
 *
 * Currently a library helper without a production caller. Phase 4 (drift
 * detection) will wire `validateAppendOnly` into the manifest-update path
 * driven by the drift sub-agent: drafted manifests will be parsed,
 * validated against the on-disk version, and only then surfaced via
 * `propose_write`. Until then, all manifest writes go through Claude's
 * direct `propose_write` calls in the `/seed-workspace` flow, with the
 * append-only invariant communicated as English in the seed prompt.
 *
 * Round-trips with [WorkspaceManifestParser] for any well-formed
 * manifest (parse(render(m)) == m).
 */
object WorkspaceManifestRenderer {

    fun render(manifest: WorkspaceManifest): String = buildString {
        appendLine("# Workspace: ${manifest.name.ifBlank { "(unnamed)" }}")
        for (group in manifest.groups) {
            appendLine()
            if (group.name == RepoGroup.DEFAULT_NAME) appendLine("## Repos")
            else appendLine("## Repos: ${group.name}")
            if (group.dependsOn.isNotEmpty()) {
                appendLine("_dependsOn: ${group.dependsOn.joinToString(", ")}_")
            }
            appendLine()
            for (repo in group.repos) {
                append("- **${repo.key}** `${repo.path}` — ${repo.role}")
                if (repo.jiraPrefixes.isNotEmpty()) {
                    append(" _jira: ${repo.jiraPrefixes.joinToString(", ")}_")
                }
                appendLine()
            }
        }
    }

    /**
     * Throws IllegalStateException if `after` mutates, removes, or moves
     * any repo present in `before`. New entries / new groups are allowed.
     * Compares by key (the manifest's stable identifier).
     */
    fun validateAppendOnly(before: WorkspaceManifest, after: WorkspaceManifest) {
        for (group in before.groups) {
            for (repo in group.repos) {
                val afterGroup = after.groupOf(repo.key)
                    ?: throw IllegalStateException(
                        "append-only violation: existing repo '${repo.key}' was removed"
                    )
                val afterEntry = afterGroup.repos.firstOrNull { it.key == repo.key }
                    ?: throw IllegalStateException(
                        "append-only violation: existing repo '${repo.key}' was removed"
                    )
                if (afterEntry != repo) {
                    throw IllegalStateException(
                        "append-only violation: existing repo '${repo.key}' was mutated " +
                        "(before=$repo after=$afterEntry)"
                    )
                }
                if (afterGroup.name != group.name) {
                    throw IllegalStateException(
                        "append-only violation: existing repo '${repo.key}' was moved " +
                        "from group '${group.name}' to group '${afterGroup.name}'"
                    )
                }
            }
            // dep edges (set semantics — order-insensitive)
            val afterGroupForName = after.groups.firstOrNull { it.name == group.name } ?: continue
            val removedEdges = group.dependsOn.toSet() - afterGroupForName.dependsOn.toSet()
            if (removedEdges.isNotEmpty()) {
                throw IllegalStateException(
                    "append-only violation: group '${group.name}' dependsOn edge(s) " +
                    "${removedEdges.map { "'$it'" }} were removed"
                )
            }
        }
    }
}
