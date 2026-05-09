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

import com.adobe.clawdea.chat.editreview.EditDiffReviewer
import com.adobe.clawdea.chat.editreview.EditOutcome
import com.adobe.clawdea.commands.CommandCategory
import com.adobe.clawdea.commands.CommandContext
import com.adobe.clawdea.commands.CommandHandler
import com.adobe.clawdea.commands.CommandInfo
import com.adobe.clawdea.knowledge.notes.NoteContentBuilder
import com.adobe.clawdea.knowledge.notes.NotesPaths
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.time.LocalDateTime

class NoteAppendHandler(
    private val project: Project,
    private val scope: CoroutineScope,
) : CommandHandler {
    override val info = CommandInfo(
        "/note",
        "Append a timestamped entry to the personal CURRENT.md",
        CommandCategory.LOCAL,
    )

    override fun execute(args: String, context: CommandContext) {
        val currentMd = NotesPaths.currentMd(project)
        if (currentMd == null) {
            context.appendHtml("""<div class="info-block">No project basePath; cannot write note.</div>""")
            return
        }

        // The input prompt runs on EDT; we kick off the diff dialog (which must NOT run on EDT) in a coroutine.
        ApplicationManager.getApplication().invokeLater {
            val body = Messages.showMultilineInputDialog(
                project,
                "Body of the new note. It will be prepended to CURRENT.md with a timestamp heading.",
                "/note",
                args.takeIf { it.isNotBlank() },
                Messages.getQuestionIcon(),
                null,
            )
            if (body.isNullOrBlank()) return@invokeLater

            scope.launch(Dispatchers.IO) {
                val existing: String? = if (Files.exists(currentMd)) {
                    try {
                        Files.readString(currentMd)
                    } catch (e: java.io.IOException) {
                        val safeMsg = (e.message ?: e.javaClass.simpleName)
                            .replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                        ApplicationManager.getApplication().invokeLater {
                            context.appendHtml(
                                """<div class="info-block">Failed to read existing CURRENT.md: $safeMsg</div>""",
                            )
                        }
                        return@launch
                    }
                } else null
                val newContent = NoteContentBuilder.prepend(existing, body, LocalDateTime.now())
                val reviewer = EditDiffReviewer(project)
                val outcome = reviewer.review(
                    filePath = currentMd.toString(),
                    originalContent = existing.orEmpty(),
                    proposedContent = newContent,
                )
                when (outcome.outcome) {
                    EditOutcome.ACCEPTED -> {
                        reviewer.applyContent(currentMd.toString(), newContent)
                        ApplicationManager.getApplication().invokeLater {
                            context.appendHtml("""<div class="info-block">Note appended to CURRENT.md.</div>""")
                        }
                    }
                    EditOutcome.MODIFIED -> {
                        val finalContent = outcome.modifiedContent ?: newContent
                        reviewer.applyContent(currentMd.toString(), finalContent)
                        ApplicationManager.getApplication().invokeLater {
                            context.appendHtml("""<div class="info-block">Note appended to CURRENT.md (with your edits).</div>""")
                        }
                    }
                    EditOutcome.REJECTED -> {
                        ApplicationManager.getApplication().invokeLater {
                            context.appendHtml("""<div class="info-block">Note rejected — CURRENT.md unchanged.</div>""")
                        }
                    }
                    EditOutcome.DISMISSED -> {
                        // Dialog dismissed (Esc/X) — no feedback, matches existing convention.
                    }
                }
            }
        }
    }
}
