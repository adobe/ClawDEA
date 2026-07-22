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
package com.adobe.clawdea.chat.editreview

import com.adobe.clawdea.chat.FilesystemRefreshCoordinator

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.history.FileRevisionTimestampComparator
import com.intellij.history.LocalHistory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.GitUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import java.util.concurrent.CountDownLatch
import javax.swing.Action
import javax.swing.JComponent

enum class EditOutcome {
    ACCEPTED,
    REJECTED,
    MODIFIED,
    DISMISSED,
}

/**
 * Opens a custom diff dialog with explicit Accept/Reject buttons.
 * The outcome is determined by which button the user clicks, not by
 * content comparison.
 */
class EditDiffReviewer(private val project: Project) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(EditDiffReviewer::class.java)

    /**
     * Show a diff dialog and block until the user clicks Accept or Reject.
     * Called from MCP handler thread or coroutine — must NOT be called from EDT.
     */
    fun review(
        filePath: String,
        originalContent: String,
        proposedContent: String,
    ): ReviewResult {
        val latch = CountDownLatch(1)
        var result = ReviewResult(EditOutcome.REJECTED, null)
        val fileName = filePath.substringAfterLast('/')
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)

        log.info("review: scheduling dialog on EDT for $filePath (thread=${Thread.currentThread().name})")
        ApplicationManager.getApplication().invokeAndWait({
            log.info("review: EDT block executing for $filePath")
            try {
                val factory = DiffContentFactory.getInstance()
                val leftContent = factory.create(project, originalContent, fileType)
                val rightContent = factory.createEditable(project, proposedContent, fileType)
                val rightDocument = rightContent.document

                val request = SimpleDiffRequest(
                    "Review Edit: $fileName",
                    leftContent,
                    rightContent,
                    "Current",
                    "Proposed (editable)",
                )

                val diffPanel = DiffManager.getInstance().createRequestPanel(project, {}, null)
                diffPanel.setRequest(request)

                val dialog = EditReviewDialog(project, diffPanel.component, fileName)
                dialog.show()

                when (dialog.outcome) {
                    EditOutcome.ACCEPTED -> {
                        val rightText = rightDocument.text
                        val outcome = determineAcceptOutcome(proposedContent, rightText)
                        val modifiedContent = if (outcome == EditOutcome.MODIFIED) rightText else null
                        result = ReviewResult(outcome, modifiedContent)
                    }
                    EditOutcome.REJECTED -> {
                        result = ReviewResult(EditOutcome.REJECTED, null)
                    }
                    else -> {
                        result = ReviewResult(EditOutcome.DISMISSED, null)
                    }
                }
            } finally {
                latch.countDown()
            }
        }, ModalityState.defaultModalityState())

        latch.await()
        log.info("review: returning ${result.outcome} for $filePath")
        return result
    }

    /**
     * Resolve [filePath] to an absolute file. A relative path is resolved against the
     * project base dir (mirrors [com.adobe.clawdea.mcp.PsiUtils]) — NOT the JVM working
     * directory, which for an IDE launched from Finder/Dock is typically `/`. Non-Claude
     * agent backends (OpenAI-compatible, Codex) sometimes emit relative `file_path`
     * arguments despite the tool schema asking for absolute; without this a write would
     * silently land off-project (or fail `mkdirs` under `/`).
     */
    private fun resolveFile(filePath: String): File {
        if (filePath.startsWith("/")) return File(filePath)
        val base = project.basePath ?: return File(filePath)
        return File(base, filePath)
    }

    /**
     * Apply content to a file on disk. Returns true if the write landed, false if it did
     * not (e.g. parent directory could not be created). Callers that surface a success
     * message to the model MUST check this — reporting "ACCEPTED" for a write that never
     * happened silently loses the user's/model's work (observed with relative paths that
     * resolved under an unwritable CWD).
     *
     * Document-backed paths go through WriteCommandAction so the write runs in
     * a write-safe transactional context regardless of the caller's modality.
     * Callers may reach us from a Dispatchers.Default coroutine where
     * ModalityState.defaultModalityState() resolves to NON_MODAL, which
     * TransactionGuard rejects for document mutations.
     */
    fun applyContent(filePath: String, content: String): Boolean {
        val file = resolveFile(filePath)
        val absolutePath = file.path
        // Create parent dirs on demand. Previously this method bailed when the parent
        // didn't exist, which silently swallowed writes for new files in new subdirs
        // (e.g. .claude/wiki/concepts/<name>.md when /seed-wiki proposes a fresh wiki).
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("applyContent: could not create parent dir for $absolutePath; write skipped")
            return false
        }

        val lfs = LocalFileSystem.getInstance()
        val docManager = FileDocumentManager.getInstance()
        val preExistingVFile = lfs.findFileByPath(absolutePath)
        val openDoc = preExistingVFile?.let { docManager.getCachedDocument(it) }

        if (openDoc != null) {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                openDoc.setText(content)
                docManager.saveDocument(openDoc)
            }
        } else {
            ApplicationManager.getApplication().invokeAndWait({
                WriteAction.runAndWait<Exception> {
                    file.writeText(content)
                }
                lfs.refreshAndFindFileByPath(absolutePath)?.refresh(false, false)
            }, ModalityState.nonModal())
        }

        project.getService(FilesystemRefreshCoordinator::class.java).onEditApplied(absolutePath)
        return true
    }

    /**
     * Review a stale edit by finding its pre-edit content from Local History or git.
     * Returns null if no historical content can be found.
     * Must NOT be called from EDT.
     */
    fun reviewFromHistory(filePath: String, editTimestampMs: Long): HistoryReviewResult? {
        val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
        val currentContent = java.io.File(filePath).let { if (it.exists()) it.readText() else return null }

        // Try Local History first: find the latest revision at or before the edit timestamp
        val originalContent = findLocalHistoryContent(vf, editTimestampMs)
            ?: findGitContent(filePath)
            ?: return null

        if (originalContent == currentContent) {
            // File hasn't changed since the historical version — nothing to review
            return HistoryReviewResult(EditOutcome.ACCEPTED, originalContent, null)
        }

        val result = review(filePath, originalContent, currentContent)
        return HistoryReviewResult(result.outcome, originalContent, result.modifiedContent)
    }

    private fun findLocalHistoryContent(vf: com.intellij.openapi.vfs.VirtualFile?, editTimestampMs: Long): String? {
        if (vf == null) return null
        return try {
            val byteContent = LocalHistory.getInstance().getByteContent(
                vf,
                FileRevisionTimestampComparator { revisionTimestamp ->
                    revisionTimestamp < editTimestampMs
                },
            )
            if (byteContent != null) {
                String(byteContent, Charsets.UTF_8)
            } else {
                null
            }
        } catch (e: Exception) {
            log.info("Local History lookup failed for ${vf.path}: ${e.message}")
            null
        }
    }

    private fun findGitContent(filePath: String): String? {
        return try {
            val repos = GitUtil.getRepositories(project)
            if (repos.isEmpty()) return null
            val repo = repos.first()
            val root = repo.root
            val relativePath = filePath.removePrefix(root.path).removePrefix("/")

            val handler = GitLineHandler(project, root, GitCommand.SHOW)
            handler.addParameters("HEAD:$relativePath")
            val result = Git.getInstance().runCommand(handler)
            if (result.success()) {
                result.outputAsJoinedString
            } else {
                null
            }
        } catch (e: Exception) {
            log.info("Git content lookup failed for $filePath: ${e.message}")
            null
        }
    }

    data class ReviewResult(
        val outcome: EditOutcome,
        val modifiedContent: String?,
    )

    data class HistoryReviewResult(
        val outcome: EditOutcome,
        val originalContent: String,
        val modifiedContent: String?,
    )

    companion object {
        /**
         * When Accept is clicked, determine if content was modified.
         */
        fun determineAcceptOutcome(
            proposedContent: String,
            rightContent: String,
        ): EditOutcome {
            return if (rightContent == proposedContent) EditOutcome.ACCEPTED else EditOutcome.MODIFIED
        }

        /**
         * Build a feedback line for one edit review outcome.
         */
        fun buildFeedbackMessage(
            outcome: EditOutcome,
            filePath: String,
            originalContent: String,
            modifiedContent: String?,
        ): String? {
            return when (outcome) {
                EditOutcome.ACCEPTED -> {
                    "- $filePath: ACCEPTED (edit applied as proposed)"
                }
                EditOutcome.REJECTED -> {
                    "- $filePath: REJECTED (file unchanged, please reconsider your approach)"
                }
                EditOutcome.MODIFIED -> {
                    val modified = modifiedContent ?: return "- $filePath: MODIFIED"
                    val lines = modified.lines()
                    val summary = if (lines.size <= 200) {
                        modified
                    } else {
                        val first = lines.take(10).joinToString("\n")
                        val last = lines.takeLast(10).joinToString("\n")
                        "$first\n...(${lines.size} lines total)...\n$last"
                    }
                    "- $filePath: MODIFIED (user applied a different version)\n\nModified content for $filePath:\n$summary"
                }
                EditOutcome.DISMISSED -> null
            }
        }
    }
}

/**
 * Custom dialog that wraps a diff panel with Accept and Reject buttons.
 * Close (X) and Escape are treated as Reject.
 */
class EditReviewDialog(
    project: Project,
    private val diffComponent: JComponent,
    fileName: String,
) : DialogWrapper(project, true) {

    var outcome: EditOutcome = EditOutcome.DISMISSED
        private set

    private val rejectAction = object : DialogWrapperAction("Reject") {
        override fun doAction(e: java.awt.event.ActionEvent?) {
            outcome = EditOutcome.REJECTED
            close(CANCEL_EXIT_CODE)
        }
    }

    init {
        title = "Review Edit: $fileName"
        setOKButtonText("Accept")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = javax.swing.JPanel(BorderLayout())
        panel.preferredSize = Dimension(800, 600)
        panel.add(diffComponent, BorderLayout.CENTER)
        return panel
    }

    override fun doOKAction() {
        outcome = EditOutcome.ACCEPTED
        super.doOKAction()
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction, rejectAction)
    }
}
