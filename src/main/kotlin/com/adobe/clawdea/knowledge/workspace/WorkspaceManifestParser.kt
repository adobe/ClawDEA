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

import java.nio.file.Path

object WorkspaceManifestParser {

    private val NAME_HEADING_RX = Regex("^#\\s+Workspace:\\s+(.+?)\\s*$")
    // Matches `## Repos`, `## Repos:`, or `## Repos: <name>`. Captured group is the optional name.
    private val REPOS_HEADING_RX = Regex(
        "^##\\s+Repos(?::(?:\\s+(.+?))?)?\\s*$",
        RegexOption.IGNORE_CASE,
    )
    // Matches a generic level-2 heading: `## <name>`. Used to capture a group name that
    // appears on its own line just before a bare `## Repos[:]` heading (two-line shape).
    private val GENERIC_H2_RX = Regex("^##\\s+(.+?)\\s*$")
    // - **<key>** `<path>` — <role>[ _jira: <list>_]
    // Em-dash only (U+2014); hyphen/double-hyphen are treated as role text.
    private val REPO_BULLET_RX = Regex(
        "^-\\s+\\*\\*([a-z0-9-]+)\\*\\*\\s+`([^`]+)`\\s+—\\s+(.*?)(?:\\s+_jira:\\s*([^_]+)_)?\\s*$"
    )
    private val DEPENDS_ON_RX = Regex("^_dependsOn:\\s*(.+?)\\s*_\\s*$")

    fun parse(text: String, discoveredAt: Path? = null): WorkspaceManifest {
        var name = ""
        val groups = mutableListOf<MutableGroup>()
        var current: MutableGroup? = null
        // Captures a `## <name>` heading that precedes a bare `## Repos[:]` heading
        // (two-line shape). Consumed when the next `## Repos` heading is found.
        var pendingGroupName: String? = null

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (name.isEmpty()) {
                NAME_HEADING_RX.matchEntire(line)?.let {
                    name = it.groupValues[1].trim()
                    continue
                }
            }

            val reposMatch = REPOS_HEADING_RX.matchEntire(line)
            if (reposMatch != null) {
                val capturedName = reposMatch.groupValues.getOrNull(1)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val groupName = capturedName ?: pendingGroupName ?: RepoGroup.DEFAULT_NAME
                current = MutableGroup(groupName).also { groups += it }
                pendingGroupName = null
                continue
            }

            val cur = current
            if (cur != null) {
                val depsMatch = DEPENDS_ON_RX.matchEntire(line)
                if (depsMatch != null) {
                    cur.dependsOn = depsMatch.groupValues[1]
                        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    continue
                }
            }

            // A non-Repos `## <name>` heading: stash the name as a pending group label
            // for the next `## Repos[:]` heading and close the current section.
            val genericMatch = GENERIC_H2_RX.matchEntire(line)
            if (genericMatch != null) {
                pendingGroupName = genericMatch.groupValues[1].trim()
                current = null
                continue
            }

            // Any other heading closes the current Repos section.
            if (line.startsWith("#")) {
                current = null
                pendingGroupName = null
                continue
            }

            if (cur == null) continue
            val match = REPO_BULLET_RX.matchEntire(line) ?: continue
            val jira = match.groupValues.getOrNull(4).orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            cur.repos += RepoEntry(
                key = match.groupValues[1],
                path = match.groupValues[2],
                role = match.groupValues[3].trim(),
                jiraPrefixes = jira,
            )
        }

        return WorkspaceManifest(
            name = name,
            groups = groups.map { RepoGroup(it.name, it.repos.toList(), it.dependsOn) },
            discoveredAt = discoveredAt,
        )
    }

    private class MutableGroup(val name: String) {
        val repos = mutableListOf<RepoEntry>()
        var dependsOn: List<String> = emptyList()
    }
}
