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
package com.adobe.clawdea.knowledge.primer

import com.adobe.clawdea.knowledge.notes.NotesSource
import com.adobe.clawdea.knowledge.repostate.RepoStateGenerator
import com.adobe.clawdea.knowledge.repostate.RepoStateWriter
import com.adobe.clawdea.knowledge.primer.sources.ClaudeMdSource
import com.adobe.clawdea.knowledge.primer.sources.RepoStateSource
import com.adobe.clawdea.knowledge.primer.sources.SiblingsSource
import com.adobe.clawdea.knowledge.primer.sources.WikiIndexSource
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class PrimerService(private val project: Project) {

    private val log = Logger.getInstance(PrimerService::class.java)
    private val cached = AtomicReference<String>("")
    // Order: user authority (claudeMd) → curated orientation (wikiIndex) →
    // cross-project orientation (siblings, when in a workspace) → mechanical
    // repo-state snapshot (repoState). The wiki directive must land before
    // the agent has scanned the repo-state snapshot, otherwise hot-file
    // proximity makes the wiki feel redundant and the probe gets skipped —
    // observed across sessions 9b36ff6b, 537c8342, 1afd97af, 2d41a87f.
    private val sources: List<PrimerSource> = listOf(
        ClaudeMdSource(),
        WikiIndexSource(),
        NotesSource(),
        SiblingsSource(),
        RepoStateSource(),
    )

    fun refreshAndGet(): String {
        val settings = ClawDEASettings.getInstance().state
        if (!settings.enableKnowledgeLayer) return ""

        val basePath = project.basePath
        if (basePath == null) {
            log.warn("PrimerService: project has no basePath; skipping refresh")
            return cached.get()
        }

        if (settings.enableWikiLibrarian) {
            val claudeDir = Paths.get(basePath).resolve(settings.claudeDirName)
            val r = com.adobe.clawdea.knowledge.wiki.WikiLibrarianInstaller().ensureInstalled(claudeDir)
            if (r is com.adobe.clawdea.knowledge.wiki.WikiLibrarianInstaller.InstallResult.Failed) {
                log.warn("WikiLibrarianInstaller failed: ${r.cause.message}")
            }
        }

        val repoStateStart = System.currentTimeMillis()
        val repoStateText = try {
            RepoStateGenerator(project).generate()
        } catch (e: Throwable) {
            log.warn("RepoStateGenerator threw during refresh", e)
            null
        }
        val repoStateDur = System.currentTimeMillis() - repoStateStart
        if (repoStateDur > settings.repoStateWarnThresholdMs) {
            log.warn("REPO_STATE regeneration took ${repoStateDur}ms (warn threshold: ${settings.repoStateWarnThresholdMs}ms)")
        }
        if (repoStateText != null) {
            try {
                RepoStateWriter.write(
                    projectRoot = Paths.get(basePath),
                    claudeDirName = settings.claudeDirName,
                    content = repoStateText,
                )
            } catch (e: Throwable) {
                log.warn("RepoStateWriter failed", e)
            }
        }

        val parts = linkedMapOf<String, String?>()
        for (source in sources) {
            // Prefer the freshly-generated REPO_STATE over the disk copy: if RepoStateWriter
            // just failed (bad permissions, FS error), RepoStateSource would return
            // a stale or empty body while we have the correct content in memory.
            if (source is RepoStateSource && repoStateText != null) {
                parts[source.id] = repoStateText.trim().takeIf { it.isNotEmpty() }
                continue
            }
            val body = try {
                source.load(project)
            } catch (e: Throwable) {
                log.warn("PrimerSource '${source.id}' threw", e)
                null
            }
            parts[source.id] = body
        }
        val payload = PrimerAssembler.assemble(parts)
        cached.set(payload)
        return payload
    }

    companion object {
        fun getInstance(project: Project): PrimerService =
            project.getService(PrimerService::class.java)
    }
}
