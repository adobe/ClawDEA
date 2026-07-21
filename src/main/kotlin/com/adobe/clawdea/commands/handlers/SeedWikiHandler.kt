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
package com.adobe.clawdea.commands.handlers

import com.adobe.clawdea.chat.permission.AskUserQuestionInput
import com.adobe.clawdea.chat.permission.HandlerQuestionAnswers
import com.adobe.clawdea.commands.CommandCategory
import com.adobe.clawdea.commands.CommandContext
import com.adobe.clawdea.commands.CommandHandler
import com.adobe.clawdea.commands.CommandInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Handles `/seed-wiki` — opens an interactive placement question (local-only
 * vs Git-shareable) and then dispatches a path-aware bootstrap prompt to the
 * CLI. For the shareable choice, also writes `.clawdea/config.json` and the
 * gitignore entry so the project enters team mode before the CLI starts
 * generating files.
 *
 * Replaces the older [BridgeExpandingHandler] wiring of `/seed-wiki` (which
 * always assumed `.claude/wiki/`). The expansion lambda is supplied by
 * `ChatPanel` so the prompt template stays co-located with the other
 * BridgeExpanding prompts; the handler focuses on UX flow + setup.
 */
class SeedWikiHandler(
    private val project: Project,
    private val expansion: (wikiPathRel: String) -> String,
) : CommandHandler {

    enum class Placement { LOCAL_ONLY, SHAREABLE }

    override val info = CommandInfo(
        "/seed-wiki",
        "Bootstrap initial wiki pages from project state",
        CommandCategory.LOCAL,
    )

    override fun execute(args: String, context: CommandContext) {
        val ask = context.askQuestion
        val seed = context.runSeedWiki
        if (ask == null || seed == null) {
            // Headless fallback — with no placement question available, bootstrap a
            // default-mode wiki so non-chat call sites (tests, future scripted entry
            // points) still work. The seed itself runs under the WIKI role via
            // runSeedWiki, not the chat bridge.
            context.appendHtml(
                """<div class="info-block">/seed-wiki: bootstrapping wiki at $DEFAULT_LOCAL_WIKI_PATH/...</div>""",
            )
            seed?.invoke(expansion(DEFAULT_LOCAL_WIKI_PATH))
            return
        }

        val basePath = project.basePath
        if (basePath == null) {
            context.appendHtml("""<div class="info-block">/seed-wiki: no project base path.</div>""")
            return
        }
        val projectBase = Path.of(basePath)

        val prefill = args.trim().ifBlank { DEFAULT_SHAREABLE_WIKI_PATH }
        ask(buildQuestion(prefill)) { resolved ->
            if (resolved == null) {
                context.appendHtml("""<div class="info-block">/seed-wiki cancelled.</div>""")
                return@ask
            }
            handleResolution(projectBase, resolved, context, seed)
        }
    }

    private fun handleResolution(
        projectBase: Path,
        resolved: HandlerQuestionAnswers,
        context: CommandContext,
        seed: (String) -> Unit,
    ) {
        val placementLabel = resolved.answers.values.firstOrNull()
        val placement = parsePlacement(placementLabel)
        if (placement == null) {
            context.appendHtml(
                """<div class="info-block">/seed-wiki: choose '$LOCAL_ONLY_LABEL' or '$SHAREABLE_LABEL'.</div>""",
            )
            return
        }

        when (placement) {
            Placement.LOCAL_ONLY -> {
                context.appendHtml(
                    """<div class="info-block">/seed-wiki: bootstrapping wiki at $DEFAULT_LOCAL_WIKI_PATH/...</div>""",
                )
                seed(expansion(DEFAULT_LOCAL_WIKI_PATH))
            }
            Placement.SHAREABLE -> {
                val newPath = resolved.freeforms.values.firstOrNull()?.trim().orEmpty()
                val pathErr = WikiRelocateHandler.validatePath(newPath)
                if (pathErr != null) {
                    context.appendHtml(
                        """<div class="info-block">/seed-wiki: ${escapeHtml(pathErr)}</div>""",
                    )
                    return
                }
                runShareableSetup(projectBase, newPath, context, seed)
            }
        }
    }

    /**
     * Off-EDT phase for the shareable path: writes `.clawdea/config.json`,
     * appends the gitignore entry, refreshes the VFS (so the new path
     * becomes visible), then dispatches the expanded prompt. Failures
     * surface as a single info-block on the chat — no exception leaks.
     */
    private fun runShareableSetup(
        projectBase: Path,
        newPath: String,
        context: CommandContext,
        seed: (String) -> Unit,
    ) {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        val heavy = Runnable {
            try {
                WikiRelocateHandler.writeConfig(projectBase, newPath)
                // Switching to team mode: remove any empty default-mode wiki dir
                // (e.g. a stale .clawdea/wiki) so it doesn't dangle.
                WikiRelocateHandler.removeEmptyDefaultWikiTrees(projectBase, WikiRelocateHandler.safeWikiSubdir(), projectBase.resolve(newPath))
                WikiRelocateHandler.appendGitignore(projectBase, ".clawdea/wiki-state.local.json")
                project.getService(com.adobe.clawdea.chat.FilesystemRefreshCoordinator::class.java)
                    ?.onMassFileChange()
                postUi {
                    context.appendHtml(
                        """<div class="info-block">/seed-wiki: configured shareable wiki at '${escapeHtml(newPath)}'. Bootstrapping...</div>""",
                    )
                    seed(expansion(newPath))
                }
            } catch (e: Throwable) {
                LOG.warn("/seed-wiki shareable setup failed: ${e.message}", e)
                postUi {
                    context.appendHtml(
                        """<div class="info-block">/seed-wiki failed: ${escapeHtml(e.message ?: e.toString())}</div>""",
                    )
                }
            }
        }
        if (app != null) app.executeOnPooledThread(heavy) else heavy.run()
    }

    private fun postUi(block: () -> Unit) {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (app != null) app.invokeLater(block) else block()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {

        private val LOG = Logger.getInstance(SeedWikiHandler::class.java)

        const val DEFAULT_LOCAL_WIKI_PATH: String = ".clawdea/wiki"
        const val DEFAULT_SHAREABLE_WIKI_PATH: String = "docs/llm-wiki"
        const val LOCAL_ONLY_LABEL: String = "local only"
        const val SHAREABLE_LABEL: String = "shareable via git"

        /** Maps an AskUserQuestion label to a [Placement], case-insensitive. */
        fun parsePlacement(label: String?): Placement? = when (label?.lowercase()?.trim()) {
            LOCAL_ONLY_LABEL -> Placement.LOCAL_ONLY
            SHAREABLE_LABEL -> Placement.SHAREABLE
            else -> null
        }

        /**
         * Builds the AskUserQuestion payload for the placement prompt.
         * The freeform field is per-question (not per-option) — the renderer
         * displays it once below the radios. The label clarifies it only
         * applies to the shareable path.
         */
        fun buildQuestion(shareablePathPrefill: String = DEFAULT_SHAREABLE_WIKI_PATH): AskUserQuestionInput {
            val q = AskUserQuestionInput.Question(
                question = "Where do you want the wiki to be stored?",
                header = "Seed wiki",
                options = listOf(
                    AskUserQuestionInput.Option(
                        label = LOCAL_ONLY_LABEL,
                        description = "Bootstrap the wiki at $DEFAULT_LOCAL_WIKI_PATH/. Not committed to Git — each developer creates and maintains their own.",
                    ),
                    AskUserQuestionInput.Option(
                        label = SHAREABLE_LABEL,
                        description = "Bootstrap the wiki at the path below, write .clawdea/config.json so collaborators inherit the location, and gitignore the per-user state file.",
                    ),
                ),
                multiSelect = false,
                freeformInput = AskUserQuestionInput.FreeformInput(
                    prefill = shareablePathPrefill,
                    label = "Path (only used for '$SHAREABLE_LABEL'):",
                    placeholder = DEFAULT_SHAREABLE_WIKI_PATH,
                ),
            )
            return AskUserQuestionInput(questions = listOf(q))
        }
    }
}
