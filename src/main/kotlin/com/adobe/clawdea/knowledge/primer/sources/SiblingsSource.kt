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
package com.adobe.clawdea.knowledge.primer.sources

import com.adobe.clawdea.knowledge.primer.PrimerSource
import com.adobe.clawdea.knowledge.workspace.SiblingsRenderer
import com.adobe.clawdea.knowledge.workspace.WorkspaceDiscovery
import com.adobe.clawdea.knowledge.workspace.WorkspaceManifest
import com.adobe.clawdea.knowledge.workspace.WorkspaceManifestFilter
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.nio.file.Paths

class SiblingsSource : PrimerSource {

    override val id = "siblings"

    override fun load(project: Project): String? {
        val state = ClawDEASettings.getInstance().state
        if (!state.enableWorkspace) return null
        val basePath = project.basePath ?: return null
        return loadFor(
            projectBasePath = Paths.get(basePath),
            manifestName = state.workspaceManifestName,
            wikiSubdir = state.wikiSubdir,
        )
    }

    companion object {

        /**
         * Pure path-based entry point so the renderer + discovery flow can
         * be tested without an IntelliJ Project fixture. Walks up to find
         * the manifest, renders the siblings text, and returns it. Returns
         * null when no manifest is found above projectBasePath.
         *
         * The rendered text is consumed in-memory by the primer only — it is
         * not persisted to disk (nothing reads a `SIBLINGS.md` file back).
         */
        fun loadFor(
            projectBasePath: Path,
            manifestName: String,
            wikiSubdir: String = "wiki",
        ): String? {
            val manifestPath = WorkspaceDiscovery.discover(projectBasePath, manifestName) ?: return null
            val rawManifest = WorkspaceDiscovery.parseManifest(manifestPath) ?: return null
            val manifest = WorkspaceManifestFilter.filterToCurrentGroup(
                rawManifest,
                manifestPath.parent,
                projectBasePath,
            )

            val currentRepoKey = inferCurrentRepoKey(projectBasePath, manifest, manifestPath.parent)
            val currentGroupName = manifest.groups.firstOrNull { group ->
                group.repos.any { it.key == currentRepoKey }
            }?.name
            val rendered = SiblingsRenderer.render(
                manifest,
                currentRepoKey,
                currentGroupName,
                wikiSubdir = wikiSubdir,
            )

            return rendered.trim().takeIf { it.isNotEmpty() }
        }

        /**
         * Identifies the current repo by matching resolved paths (not key
         * names). Lets users name a repo whatever they want — `(this repo)`
         * still annotates the entry whose path resolves to the current
         * project root. Returns null if no entry matches.
         */
        private fun inferCurrentRepoKey(
            projectBasePath: Path,
            manifest: WorkspaceManifest,
            manifestDir: Path,
        ): String? {
            val target = projectBasePath.toAbsolutePath().normalize()
            for (repo in manifest.repos) {
                val resolved = repo.resolvedPath(manifestDir)
                if (resolved == target) return repo.key
            }
            return null
        }
    }
}
