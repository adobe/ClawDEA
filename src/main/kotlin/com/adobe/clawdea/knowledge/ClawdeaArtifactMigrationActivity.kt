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
package com.adobe.clawdea.knowledge

import com.adobe.clawdea.commands.handlers.WikiRelocateHandler
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Silently relocates the legacy `.claude/REPO_STATE.md` (and default-mode wiki)
 * into `.clawdea/` on project open, removes any stale `SIBLINGS.md` left by older
 * versions, and (in a git repo) keeps the regenerated `REPO_STATE.md` gitignored —
 * `.clawdea/` itself is committed (it holds `config.json`), so the per-turn
 * generated file must be excluded explicitly. No-op once done.
 */
class ClawdeaArtifactMigrationActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val basePath = project.basePath ?: return
        val projectRoot = Paths.get(basePath)
        val state = ClawDEASettings.getInstance().state
        val claudeDirName = state.claudeDirName
        try {
            ClawdeaArtifactMigrator.migrate(projectRoot, claudeDirName)
            ClawdeaArtifactMigrator.removeStaleSiblings(projectRoot, claudeDirName)
            ClawdeaArtifactMigrator.migrateWikiDir(projectRoot, claudeDirName, state.wikiSubdir)
        } catch (e: Throwable) {
            LOG.warn("ClawdeaArtifactMigrator failed for $basePath", e)
        }
        ensureGitignored(projectRoot)
    }

    /** Gitignore the generated artifacts so they aren't committed alongside `config.json`. */
    private fun ensureGitignored(projectRoot: Path) {
        // `.git` is a directory in a normal clone and a file in a worktree.
        if (!Files.exists(projectRoot.resolve(".git"))) return
        for (entry in GITIGNORE_ENTRIES) {
            try {
                WikiRelocateHandler.appendGitignore(projectRoot, entry)
            } catch (e: Throwable) {
                LOG.warn("Failed to gitignore $entry in $projectRoot", e)
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ClawdeaArtifactMigrationActivity::class.java)
        private val GITIGNORE_ENTRIES = listOf(".clawdea/REPO_STATE.md")
    }
}
