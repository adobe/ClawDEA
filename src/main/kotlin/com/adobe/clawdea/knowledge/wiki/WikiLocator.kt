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
package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Single source of truth for the wiki directory and the current operating mode.
 *
 * Two modes, detected by file presence:
 *  - **Default**: `.clawdea/config.json` absent → wiki at `<base>/<claudeDirName>/<wikiSubdir>`
 *    (default `.claude/wiki/`), honoring [ClawDEASettings] overrides.
 *  - **Team**: `.clawdea/config.json` present with a non-blank `wikiPath` → wiki at
 *    `<base>/<wikiPath>`. Auto-activated when cloning a repo that already has the
 *    config file.
 *
 * Team-mode reading is added in a later task. This first version only resolves the default mode.
 */
@Service(Service.Level.PROJECT)
class WikiLocator(private val project: Project) {

    /** Resolved wiki directory for this project. Recomputed on every call (cheap). */
    fun wikiDir(): Path {
        val basePath = project.basePath ?: return Paths.get(".claude", "wiki")
        val state = ClawDEASettings.getInstance().state
        return resolve(
            projectBase = Paths.get(basePath),
            claudeDirName = state.claudeDirName,
            wikiSubdir = state.wikiSubdir,
            configReader = { null },
        ).wikiDir
    }

    /** True when `.clawdea/config.json` is the source of truth. Always false in this task. */
    fun isTeamMode(): Boolean = false

    companion object {

        /** Pure resolution function, easy to unit-test without a Project. */
        fun resolve(
            projectBase: Path,
            claudeDirName: String,
            wikiSubdir: String,
            configReader: () -> String?,
        ): Resolved {
            val configWikiPath = configReader()?.takeIf { it.isNotBlank() }
            return if (configWikiPath != null) {
                Resolved(projectBase.resolve(configWikiPath), teamMode = true)
            } else {
                Resolved(projectBase.resolve(claudeDirName).resolve(wikiSubdir), teamMode = false)
            }
        }

        fun getInstance(project: Project): WikiLocator =
            project.getService(WikiLocator::class.java)
    }

    data class Resolved(val wikiDir: Path, val teamMode: Boolean)
}
