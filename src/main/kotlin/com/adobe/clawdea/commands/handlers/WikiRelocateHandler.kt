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
import com.adobe.clawdea.knowledge.wiki.WikiLocator
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Handles `/wiki-relocate [path]` — moves the wiki to a new project-relative path
 * and writes `.clawdea/config.json` so subsequent project opens enter team mode.
 *
 * The pure helpers ([validatePath], [writeConfig], [appendGitignore]) are
 * unit-testable; the Project-aware [execute] composes them and prompts the
 * user via the existing AskUserQuestion card (now extended with a freeform
 * input for the path itself — see Task 19b).
 */
class WikiRelocateHandler(private val project: Project) : CommandHandler {

    enum class Action { MOVE, COPY, NOTHING }

    override val info = CommandInfo(
        "/wiki-relocate",
        "Move the wiki to a project-relative path and commit the location to .clawdea/config.json",
        CommandCategory.LOCAL,
    )

    override fun execute(args: String, context: CommandContext) {
        val basePath = project.basePath
        if (basePath == null) {
            context.appendHtml("""<div class="info-block">Cannot relocate wiki: no project base path.</div>""")
            return
        }
        val projectBase = Path.of(basePath)
        val ask = context.askQuestion
        if (ask == null) {
            context.appendHtml(
                """<div class="info-block">Cannot relocate wiki: no interactive UI available in this context.</div>""",
            )
            return
        }

        val prefill = args.trim().ifBlank { DEFAULT_WIKI_PATH }
        val input = buildQuestion(prefill)

        ask(input) { resolved ->
            if (resolved == null) {
                context.appendHtml(
                    """<div class="info-block">/wiki-relocate cancelled — no changes made.</div>""",
                )
                return@ask
            }
            completeRelocate(projectBase, resolved, context)
        }
    }

    private fun completeRelocate(
        projectBase: Path,
        resolved: HandlerQuestionAnswers,
        context: CommandContext,
    ) {
        val newPath = resolved.freeforms.values.firstOrNull()?.trim().orEmpty()
        val actionLabel = resolved.answers.values.firstOrNull()
        val action = parseAction(actionLabel)
        if (action == null) {
            context.appendHtml(
                """<div class="info-block">Cannot relocate wiki: choose Move, Copy, or Nothing.</div>""",
            )
            return
        }
        val pathErr = validatePath(newPath)
        if (pathErr != null) {
            context.appendHtml(
                """<div class="info-block">Cannot relocate wiki: ${escapeHtml(pathErr)}</div>""",
            )
            return
        }

        val oldWikiDir = WikiLocator.getInstance(project).wikiDir()
        val newWikiDir = projectBase.resolve(newPath)
        val oldWikiRel = relativeWikiPath(projectBase, oldWikiDir)

        // The heavy path runs `git mv` and bulk file ops, which mustn't sit on
        // the EDT (the Git plugin asserts both on `OSProcessHandler.waitFor`
        // and on `BuiltInServerManager.waitForStart`). Validation above stayed
        // on EDT so existing bail-path tests keep working; everything below
        // goes to a pool thread, with UI rejoinder posted back via invokeLater.
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        val heavy = Runnable { runHeavyRelocate(projectBase, oldWikiDir, newWikiDir, oldWikiRel, newPath, action, context) }
        if (app != null) app.executeOnPooledThread(heavy) else heavy.run()
    }

    /**
     * Off-EDT phase of [completeRelocate]: file ops, config write, gitignore,
     * CLAUDE.md link rewrite, VFS refresh, drift rescan, and final UI message.
     * Any failure surfaces as an HTML info-block on the chat.
     */
    private fun runHeavyRelocate(
        projectBase: Path,
        oldWikiDir: Path,
        newWikiDir: Path,
        oldWikiRel: String,
        newPath: String,
        action: Action,
        context: CommandContext,
    ) {
        try {
            applyAction(
                oldDir = oldWikiDir,
                newDir = newWikiDir,
                action = action,
                gitMove = { src, dst -> tryGitMove(project, src, dst) },
            )
            writeConfig(projectBase, newPath)
            // Drop any now-empty default-mode wiki dir left behind (e.g. an
            // empty .clawdea/wiki) so it doesn't dangle beside the team wiki.
            removeEmptyDefaultWikiTrees(projectBase, safeWikiSubdir(), newWikiDir)
            appendGitignore(projectBase, ".clawdea/wiki-state.local.json")
            // Repoint the `## Wiki` link in CLAUDE.md (added by /seed-wiki) at
            // the new location so collaborators don't follow a stale path.
            val claudeMdUpdated = updateClaudeMdWikiLink(projectBase, oldWikiRel, newPath)

            // Project View / VFS won't notice a bulk directory move on its own;
            // push an immediate broad refresh so the relocated tree is visible
            // before the drift rescan reads it back. Skip when nothing moved.
            if (action != Action.NOTHING) {
                project.getService(com.adobe.clawdea.chat.FilesystemRefreshCoordinator::class.java)
                    ?.onMassFileChange()
            }
            // This runs on the application thread pool (executeOnPooledThread),
            // not the coroutine dispatcher, so bridging the suspend rescan with
            // runBlocking here blocks only this one-shot command's pool thread.
            project.getService(com.adobe.clawdea.knowledge.drift.DriftDetectionService::class.java)?.let { service ->
                kotlinx.coroutines.runBlocking { service.rescan() }
            }

            val actionMsg = when (action) {
                Action.NOTHING -> "left existing wiki contents in place"
                Action.MOVE -> "moved existing wiki contents to '$newPath'"
                Action.COPY -> "copied existing wiki contents to '$newPath'"
            }
            val claudeMdNote = if (claudeMdUpdated) " Updated CLAUDE.md wiki link." else ""
            postUi(context) {
                context.appendHtml(
                    """<div class="info-block">Wiki path set to '${escapeHtml(newPath)}' (${escapeHtml(actionMsg)}).${escapeHtml(claudeMdNote)} Commit .clawdea/config.json to share with your team.</div>""",
                )
            }
        } catch (e: Throwable) {
            LOG.warn("Wiki relocate failed: ${e.message}", e)
            postUi(context) {
                context.appendHtml(
                    """<div class="info-block">Wiki relocate failed: ${escapeHtml(e.message ?: e.toString())}</div>""",
                )
            }
        }
    }

    /** Project-relative POSIX form of [wikiDir], or "" if it cannot be relativized. */
    private fun relativeWikiPath(projectBase: Path, wikiDir: Path): String =
        runCatching {
            projectBase.relativize(wikiDir).toString().replace(java.io.File.separatorChar, '/')
        }.getOrNull()?.takeIf { it.isNotBlank() }.orEmpty()

    /** Posts [block] on the EDT when an Application is available; runs synchronously otherwise (unit tests). */
    private fun postUi(@Suppress("UNUSED_PARAMETER") context: CommandContext, block: () -> Unit) {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (app != null) app.invokeLater(block) else block()
    }

    private fun tryGitMove(project: Project, src: Path, dst: Path): Boolean {
        val repo = git4idea.repo.GitRepositoryManager.getInstance(project)
            .repositories.firstOrNull() ?: return false
        val handler = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.MV)
        handler.addParameters("--", src.toString(), dst.toString())
        handler.setSilent(true)
        return git4idea.commands.Git.getInstance().runCommand(handler).exitCode == 0
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {

        private val LOG = Logger.getInstance(WikiRelocateHandler::class.java)

        /** Maps an AskUserQuestion label ("Move"/"Copy"/"Nothing", case-insensitive) to [Action]. */
        fun parseAction(label: String?): Action? = when (label?.lowercase()) {
            "move" -> Action.MOVE
            "copy" -> Action.COPY
            "nothing" -> Action.NOTHING
            else -> null
        }

        /**
         * Performs the file-tree operation for [action]. For MOVE, every file goes
         * through [gitMove] first; if that returns false (or throws), falls back to
         * [Files.move]. Empty parent directories under [oldDir] are cleaned up after
         * MOVE. For COPY the source tree stays in place; for NOTHING this is a no-op.
         */
        fun applyAction(
            oldDir: Path,
            newDir: Path,
            action: Action,
            gitMove: (src: Path, dst: Path) -> Boolean,
        ) {
            when (action) {
                Action.NOTHING -> return
                Action.COPY -> {
                    if (!Files.isDirectory(oldDir)) return
                    Files.walk(oldDir).use { stream ->
                        for (src in stream) {
                            val rel = oldDir.relativize(src)
                            val dst = newDir.resolve(rel.toString())
                            if (Files.isDirectory(src)) {
                                Files.createDirectories(dst)
                            } else {
                                Files.createDirectories(dst.parent)
                                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                }
                Action.MOVE -> {
                    if (!Files.isDirectory(oldDir)) return
                    // Snapshot the file list first so we don't traverse into the
                    // destination on overlapping trees.
                    val files = Files.walk(oldDir).use { stream ->
                        stream.filter { p -> Files.isRegularFile(p) }.toList()
                    }
                    for (src in files) {
                        val rel = oldDir.relativize(src)
                        val dst = newDir.resolve(rel.toString())
                        Files.createDirectories(dst.parent)
                        val movedByGit = try {
                            gitMove(src, dst)
                        } catch (e: Throwable) {
                            LOG.warn("gitMove threw: ${e.message}")
                            false
                        }
                        if (!movedByGit) {
                            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                    // Clean up empty directories under oldDir, bottom-up. We
                    // also remove oldDir itself when empty so the legacy wiki
                    // root (e.g. `.claude/wiki/`) doesn't linger after a Move
                    // — leaving it behind would confuse collaborators about
                    // which path is canonical.
                    val dirs = Files.walk(oldDir).use { stream ->
                        stream.filter { p -> Files.isDirectory(p) }.toList()
                    }
                    for (dir in dirs.sortedByDescending { it.nameCount }) {
                        try {
                            val isEmpty = Files.list(dir).use { stream -> !stream.findAny().isPresent }
                            if (isEmpty) Files.deleteIfExists(dir)
                        } catch (_: Exception) { /* best-effort cleanup */ }
                    }
                }
            }
        }

        /** Returns null if [path] is acceptable, otherwise a human-readable error. */
        fun validatePath(path: String): String? {
            val trimmed = path.trim()
            if (trimmed.isBlank()) return "missing path argument (usage: /wiki-relocate docs/llm-wiki)"
            if (trimmed.startsWith("/") || trimmed.matches(Regex("^[A-Za-z]:.*"))) {
                return "path must be project-relative (no absolute paths)"
            }
            val normalized = trimmed.replace('\\', '/')
            if (normalized.split('/').any { it == ".." }) return "path must not traverse outside the project root"
            return null
        }

        /**
         * Configured wiki subdirectory name, defaulting to `"wiki"` when no
         * IntelliJ Application is registered (unit tests / early init) so the
         * relocation flow stays headless-safe.
         */
        fun safeWikiSubdir(): String = try {
            com.adobe.clawdea.settings.ClawDEASettings.getInstance().state.wikiSubdir
        } catch (_: Throwable) {
            "wiki"
        }

        /**
         * Removes leftover **empty** default-mode wiki trees after a project
         * enters team mode, so the old location (`.clawdea/<wikiSubdir>` and the
         * historical `.claude/<wikiSubdir>`) doesn't dangle next to the new
         * team-mode wiki. Only deletes directories with no regular files — a
         * populated legacy tree (e.g. a Copy that intentionally kept its source)
         * is preserved. The new team path [keep] is never touched.
         */
        fun removeEmptyDefaultWikiTrees(projectBase: Path, wikiSubdir: String, keep: Path) {
            val keepNorm = keep.toAbsolutePath().normalize()
            val candidates = listOf(
                projectBase.resolve(com.adobe.clawdea.CLAWDEA_DIR).resolve(wikiSubdir),
                projectBase.resolve(com.adobe.clawdea.CLAUDE_DIR).resolve(wikiSubdir),
            )
            for (dir in candidates) {
                if (!Files.isDirectory(dir)) continue
                if (dir.toAbsolutePath().normalize() == keepNorm) continue
                val hasFiles = try {
                    Files.walk(dir).use { s -> s.anyMatch { Files.isRegularFile(it) } }
                } catch (_: Throwable) { true }
                if (hasFiles) continue
                // Bottom-up empty-dir delete.
                val dirs = try {
                    Files.walk(dir).use { s -> s.filter { Files.isDirectory(it) }.toList() }
                } catch (_: Throwable) { emptyList() }
                for (d in dirs.sortedByDescending { it.nameCount }) {
                    try {
                        val empty = Files.list(d).use { !it.findAny().isPresent }
                        if (empty) Files.deleteIfExists(d)
                    } catch (_: Exception) { /* best-effort */ }
                }
            }
        }

        /** Writes `.clawdea/config.json` with `{"wikiPath":"<value>"}`. Atomic via temp+rename. */
        fun writeConfig(projectBase: Path, wikiPath: String) {
            val dir = Files.createDirectories(projectBase.resolve(".clawdea"))
            val target = dir.resolve("config.json")
            val temp = Files.createTempFile(dir, "config.json.tmp", "")
            try {
                // Hand-formatted single-line JSON. No Gson — avoids pulling its
                // pretty-printer / TypeAdapter machinery for one trivial blob.
                val escaped = wikiPath.replace("\\", "\\\\").replace("\"", "\\\"")
                Files.writeString(temp, """{"wikiPath":"$escaped"}""")
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (Files.exists(temp)) try { Files.deleteIfExists(temp) } catch (_: Exception) {}
            }
        }

        /** Default freeform prefill when the user runs `/wiki-relocate` with no argument. */
        const val DEFAULT_WIKI_PATH: String = "docs/llm-wiki"

        /**
         * Builds the AskUserQuestion payload for the move/copy/nothing prompt.
         * Pure helper — testable without a [Project] or JCEF wiring. The card
         * carries an editable freeform field prefilled with [prefillPath]
         * (the user's argument, or [DEFAULT_WIKI_PATH] when omitted) alongside
         * the radio options. The actual card rendering and submit handling
         * lives in [com.adobe.clawdea.chat.ChatPanel].
         */
        fun buildQuestion(prefillPath: String): AskUserQuestionInput {
            val q = AskUserQuestionInput.Question(
                question = "Relocate wiki — choose path and what to do with existing contents",
                header = "Wiki relocate",
                options = listOf(
                    AskUserQuestionInput.Option(
                        label = "Move",
                        description = "Move existing wiki contents to the new path. Tracked files moved with `git mv` so rename history is preserved; untracked files moved via Files.move.",
                    ),
                    AskUserQuestionInput.Option(
                        label = "Copy",
                        description = "Copy existing wiki contents to the new path. The legacy location stays in place.",
                    ),
                    AskUserQuestionInput.Option(
                        label = "Nothing",
                        description = "Just point at the new path. Old wiki untouched. You'll have to run /seed-wiki to create the wiki at the new path.",
                    ),
                ),
                multiSelect = false,
                freeformInput = AskUserQuestionInput.FreeformInput(
                    prefill = prefillPath,
                    label = "New wiki path:",
                    placeholder = DEFAULT_WIKI_PATH,
                ),
            )
            return AskUserQuestionInput(questions = listOf(q))
        }

        /**
         * Repoints the wiki link in `<projectBase>/CLAUDE.md` from
         * [oldWikiRel] to [newWikiRel] (both project-relative POSIX paths).
         * Returns true when the file was modified.
         *
         * `/seed-wiki` writes a `## Wiki` section that mentions the wiki path
         * twice in a row — once as link text inside backticks and once as the
         * Markdown URL. We rewrite occurrences of `<oldWikiRel>/` (with the
         * trailing `/` boundary) so sibling paths sharing a prefix
         * (e.g. `.claude/wiki-archive`) aren't accidentally touched.
         *
         * No-ops gracefully when:
         *  - either path is blank or both are equal (nothing to do),
         *  - CLAUDE.md is missing (project never bootstrapped one),
         *  - or no occurrence of [oldWikiRel] is found (link points elsewhere).
         */
        fun updateClaudeMdWikiLink(projectBase: Path, oldWikiRel: String, newWikiRel: String): Boolean {
            if (oldWikiRel.isBlank() || newWikiRel.isBlank() || oldWikiRel == newWikiRel) return false
            val claudeMd = projectBase.resolve("CLAUDE.md")
            if (!Files.isRegularFile(claudeMd)) return false
            val content = Files.readString(claudeMd)
            val needle = "$oldWikiRel/"
            if (!content.contains(needle)) return false
            val updated = content.replace(needle, "$newWikiRel/")
            Files.writeString(claudeMd, updated)
            return true
        }

        /**
         * Appends [entry] to `<projectBase>/.gitignore`, creating the file if
         * missing. Idempotent — does nothing if [entry] is already a line in
         * the file.
         */
        fun appendGitignore(projectBase: Path, entry: String) {
            val gitignore = projectBase.resolve(".gitignore")
            val existing = if (Files.isRegularFile(gitignore)) Files.readString(gitignore) else ""
            val lines = existing.split("\n").map { it.trim() }
            if (lines.any { it == entry }) return
            val needsNewline = existing.isNotEmpty() && !existing.endsWith("\n")
            val updated = buildString {
                append(existing)
                if (needsNewline) append("\n")
                append(entry)
                append("\n")
            }
            Files.writeString(gitignore, updated)
        }
    }
}
