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

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Path

/**
 * Restricts a manifest to the single group containing the current project.
 * If the current project's basePath is null/unresolvable, or doesn't match
 * any repo in the manifest, the manifest is returned unfiltered (preserving
 * pre-Phase-3.5 single-graph behavior for users not in their own manifest).
 */
object WorkspaceManifestFilter {

    private val LOG = Logger.getInstance(WorkspaceManifestFilter::class.java)

    fun filterToCurrentGroup(
        manifest: WorkspaceManifest,
        manifestDir: Path,
        currentBasePath: Path?,
    ): WorkspaceManifest {
        val current = currentBasePath?.let { runCatching { it.toRealPath() }.getOrNull() }
            ?: return manifest
        val matchingGroup = manifest.groups.firstOrNull { group ->
            group.repos.any { repo ->
                runCatching { repo.resolvedPath(manifestDir).toRealPath() == current }
                    .getOrDefault(false)
            }
        } ?: return manifest
        return manifest.copy(groups = transitiveClosure(manifest, matchingGroup.name))
    }

    internal fun transitiveClosure(manifest: WorkspaceManifest, startName: String): List<RepoGroup> {
        val byName = manifest.groups.associateBy { it.name }
        val visited = LinkedHashSet<String>()
        fun dfs(name: String) {
            if (!visited.add(name)) return  // cycle: already visited
            val g = byName[name] ?: return
            for (dep in g.dependsOn) {
                if (dep !in byName) {
                    LOG.warn("dependsOn '$dep' from group '$name' references unknown group; skipping")
                    continue
                }
                dfs(dep)
            }
        }
        dfs(startName)
        return visited.mapNotNull { byName[it] }
    }
}
