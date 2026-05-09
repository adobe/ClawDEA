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
import com.adobe.clawdea.knowledge.workspace.SiblingsWriter
import com.adobe.clawdea.knowledge.workspace.WorkspaceDiscovery
import com.adobe.clawdea.knowledge.workspace.WorkspaceManifest
import com.adobe.clawdea.knowledge.workspace.WorkspaceManifestFilter
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.diagnostic.Logger
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
            claudeDirName = state.claudeDirName,
            manifestName = state.workspaceManifestName,
        )
    }

    companion object {
        private val LOG = Logger.getInstance(SiblingsSource::class.java)

        /**
         * Pure path-based entry point so the renderer + discovery flow can
         * be tested without an IntelliJ Project fixture. Walks up to find
         * the manifest, renders SIBLINGS.md text, writes it to the
         * project's .claude/ dir, and returns the same text. Returns null
         * when no manifest is found above projectBasePath.
         */
        fun loadFor(projectBasePath: Path, claudeDirName: String, manifestName: String): String? {
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
            val rendered = SiblingsRenderer.render(manifest, currentRepoKey, currentGroupName)

            try {
                SiblingsWriter.write(projectRoot = projectBasePath, claudeDirName = claudeDirName, content = rendered)
            } catch (e: Throwable) {
                LOG.warn("SiblingsWriter failed for $projectBasePath", e)
            }

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
