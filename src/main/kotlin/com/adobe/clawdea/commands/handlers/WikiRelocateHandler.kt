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

import com.adobe.clawdea.commands.CommandCategory
import com.adobe.clawdea.commands.CommandContext
import com.adobe.clawdea.commands.CommandHandler
import com.adobe.clawdea.commands.CommandInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Handles `/wiki-relocate <path>` — moves the wiki to a new project-relative path
 * and writes `.clawdea/config.json` so subsequent project opens enter team mode.
 *
 * The pure helpers ([validatePath], [writeConfig], [appendGitignore]) are
 * unit-testable; the Project-aware [execute] composes them and asks the user
 * via the existing AskUserQuestion infrastructure (wired in Task 9).
 */
class WikiRelocateHandler(private val project: Project) : CommandHandler {

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
        val newPath = args.trim()

        val err = validatePath(newPath)
        if (err != null) {
            context.appendHtml("""<div class="info-block">Cannot relocate wiki: ${escapeHtml(err)}</div>""")
            return
        }

        // The full execute path (user-question card, move/copy/nothing file ops,
        // drift rescan) is wired in Tasks 9 and 10. This first version just
        // applies the config + .gitignore so the smoke path works.
        writeConfig(projectBase, newPath)
        appendGitignore(projectBase, ".clawdea/wiki-state.local.json")
        context.appendHtml(
            """<div class="info-block">Wiki path set to '${escapeHtml(newPath)}' in .clawdea/config.json. Commit this file to share with your team.</div>"""
        )
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {

        private val LOG = Logger.getInstance(WikiRelocateHandler::class.java)

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
