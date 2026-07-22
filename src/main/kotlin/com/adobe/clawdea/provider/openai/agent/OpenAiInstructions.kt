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
package com.adobe.clawdea.provider.openai.agent

import com.adobe.clawdea.cli.CliProcess
import com.adobe.clawdea.knowledge.primer.PrimerService
import com.adobe.clawdea.knowledge.prompts.PromptResource
import com.adobe.clawdea.settings.ClawDEASettings
import com.adobe.clawdea.skills.SkillInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Assembles standing instructions for OpenAI-compatible HTTP agent sessions.
 *
 * Mirrors [com.adobe.clawdea.cli.CodexInstructions] structure: tooling guidance + skill catalog + primer.
 * Must not mention any imported provider by name or include profile endpoints (generic only).
 */
object OpenAiInstructions {

    private val log = Logger.getInstance(OpenAiInstructions::class.java)

    /**
     * Builds the standing instructions for the given project/skills, honoring settings toggles.
     * Returns a composed preamble of: tool guidance + skill catalog + primer (CLAUDE.md + REPO_STATE + wiki).
     */
    fun build(project: Project?, skills: List<SkillInfo>): String {
        val settings = ClawDEASettings.getInstance().state

        val librarian =
            if (settings.enableWikiLibrarian) CliProcess.WIKI_LIBRARIAN_TOOL_PROMPT else ""

        val tooling = try {
            PromptResource.load("openai-tooling-prompt").trim()
        } catch (e: Exception) {
            log.warn("openai-tooling-prompt resource missing; using fallback guidance", e)
            buildFallbackToolingGuidance()
        }

        val skillCatalog =
            if (settings.preloadSkillCatalog && skills.isNotEmpty()) CliProcess.buildSkillCatalogPrompt(skills)
            else ""

        val primer =
            if (settings.enableKnowledgeLayer && project != null) {
                try {
                    PrimerService.getInstance(project).refreshAndGet()
                } catch (e: Exception) {
                    log.warn("PrimerService threw during openai agent start; continuing without primer", e)
                    ""
                }
            } else ""

        return compose(librarian, tooling, skillCatalog, primer)
    }

    internal fun compose(vararg blocks: String): String =
        blocks.filter { it.isNotBlank() }.joinToString("\n\n")

    /**
     * Fallback tool guidance when the resource file is missing. Provides concise
     * OpenAI function-calling instructions without mentioning any specific provider.
     */
    private fun buildFallbackToolingGuidance(): String {
        return """
            # Tool Execution Guidelines

            You have access to tools for file editing, shell commands, and project navigation.

            ## Tool Approval
            - Some tools require user approval before execution (permission system).
            - If a tool is denied, do not retry immediately - explain what you need and ask the user.
            - File edits may show a diff review dialog for the user to accept/modify/reject.

            ## Best Practices
            - Use Read tool before editing files (never edit blindly).
            - Keep shell commands safe and focused (avoid destructive operations without confirmation).
            - When editing, prefer precise targeted changes over full file rewrites.
            - Check tool results carefully - errors are communicated via tool_result messages.
        """.trimIndent()
    }
}
