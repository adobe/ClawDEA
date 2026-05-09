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

/**
 * Hand-edited list of related repos for cross-project context.
 * Lives at <workspace-root>/.clawdea-workspace.md.
 *
 * groups holds the parsed `## Repos[: <name>]` sections in source
 * order. An unnamed `## Repos` block parses as group `default`.
 */
data class WorkspaceManifest(
    val name: String,
    val groups: List<RepoGroup> = emptyList(),
    val discoveredAt: Path? = null,
) {
    val repos: List<RepoEntry> get() = groups.flatMap { it.repos }
    fun groupOf(repoKey: String): RepoGroup? =
        groups.firstOrNull { g -> g.repos.any { it.key == repoKey } }
}

data class RepoGroup(
    val name: String,
    val repos: List<RepoEntry>,
    val dependsOn: List<String> = emptyList(),
) {
    companion object { const val DEFAULT_NAME = "default" }
}

data class RepoEntry(
    val key: String,
    val path: String,
    val role: String,
    val jiraPrefixes: List<String> = emptyList(),
) {
    fun resolvedPath(manifestDir: Path): Path {
        val p = manifestDir.fileSystem.getPath(path)
        return if (p.isAbsolute) p else manifestDir.resolve(p).normalize()
    }
}
