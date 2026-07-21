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
package com.adobe.clawdea.cli

import com.adobe.clawdea.knowledge.primer.PrimerService
import com.adobe.clawdea.knowledge.prompts.PromptResource
import com.adobe.clawdea.settings.ClawDEASettings
import com.adobe.clawdea.skills.SkillInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Assembles the first-turn preamble injected into a `codex` session so the codex
 * backend reaches parity with the Claude backend's `--append-system-prompt-file`.
 *
 * Codex has no system-prompt flag, but the `codex app-server` accepts `baseInstructions`
 * on `thread/start`, which persist for the whole thread, so [CodexAppServerProcess]
 * passes this preamble there once at session start (no per-turn prepend needed).
 *
 * The preamble bundles three things, mirroring [CliProcess]'s system prompt:
 *  1. tooling + edit-routing guidance ([PromptResource] `codex-tooling-prompt`) — steers
 *     file mutations through ClawDEA's `propose_*` MCP tools (the diff-review gate), the
 *     closest we can get to Claude's Layer-1 edit review given codex's exec-mode sandbox
 *     cannot pre-gate its own shell (see the Phase-2 spike: MCP requires danger-full-access).
 *  2. the skill catalog (so `/skill` suggestions work) — reuses [CliProcess.buildSkillCatalogPrompt].
 *  3. the project primer (CLAUDE.md + REPO_STATE + wiki TOC) via [PrimerService].
 */
object CodexInstructions {

    private val log = Logger.getInstance(CodexInstructions::class.java)

    /** Builds the first-turn preamble for the given project/skills, honoring settings toggles. */
    fun build(project: Project?, skills: List<SkillInfo>): String {
        val settings = ClawDEASettings.getInstance().state
        val librarian =
            if (settings.enableWikiLibrarian) CliProcess.WIKI_LIBRARIAN_TOOL_PROMPT else ""
        val tooling = try {
            PromptResource.load("codex-tooling-prompt").trim()
        } catch (e: Exception) {
            log.warn("codex-tooling-prompt resource missing; continuing without it", e)
            ""
        }
        val skillCatalog =
            if (settings.preloadSkillCatalog && skills.isNotEmpty()) CliProcess.buildSkillCatalogPrompt(skills)
            else ""
        val primer =
            if (settings.enableKnowledgeLayer && project != null) {
                try {
                    PrimerService.getInstance(project).refreshAndGet()
                } catch (e: Exception) {
                    log.warn("PrimerService threw during codex start; continuing without primer", e)
                    ""
                }
            } else ""
        return compose(librarian, tooling, skillCatalog, primer)
    }

    /**
     * Joins the non-blank preamble blocks, and — when a preamble exists — appends the
     * user's actual request under a marker so codex can tell the standing instructions
     * from the task. Returns [userPrompt] unchanged when there is no preamble.
     */
    fun prepend(preamble: String, userPrompt: String): String =
        if (preamble.isBlank()) userPrompt
        else "$preamble\n\n---\n\nUser request:\n$userPrompt"

    internal fun compose(vararg blocks: String): String =
        blocks.filter { it.isNotBlank() }.joinToString("\n\n")
}
