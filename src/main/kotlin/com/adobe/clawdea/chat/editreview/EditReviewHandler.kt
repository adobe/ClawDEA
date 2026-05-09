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

import com.adobe.clawdea.chat.ChatBrowserRenderer
import com.adobe.clawdea.chat.MessageRenderer

import com.adobe.clawdea.cli.CliBridge
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles Layer 2 (fallback) edit review interactions from the JCEF chat panel.
 *
 * Owns the two JS-to-Kotlin query bridges (openDiff and editAction) and the
 * [EditReviewCoordinator] instance that tracks captured file contents.
 */
class EditReviewHandler(
    private val project: Project,
    private val bridge: CliBridge,
    val coordinator: EditReviewCoordinator,
    private val browserRenderer: ChatBrowserRenderer,
    private val renderer: MessageRenderer,
    private val scope: CoroutineScope,
) {

    /**
     * Registers the handler for the "open diff" JS query.
     *
     * Implements a three-tier strategy:
     * - **Tier 1:** Fresh edit — captured content still in memory.
     * - **Tier 2:** Stale edit — content cleared but timestamp available (Local History / git).
     * - **Tier 3:** No data at all (e.g., plugin restart).
     */
    fun setupOpenDiffHandler(openDiffQuery: JBCefJSQuery) {
        openDiffQuery.addHandler { toolUseId ->
            val originalContent = coordinator.getOriginalContent(toolUseId)
            val filePath = coordinator.getCapturedFilePath(toolUseId)
            val editTimestamp = coordinator.getEditTimestamp(toolUseId)

            if (originalContent != null && filePath != null) {
                // Tier 1: fresh edit — captured content still in memory.
                // Launch on Dispatchers.Default: review() internally uses
                // invokeAndWait to block on the diff dialog, which deadlocks
                // (or returns prematurely with 0-diff) when called on EDT.
                scope.launch(Dispatchers.Default) {
                    val currentContent = java.io.File(filePath).let { if (it.exists()) it.readText() else "" }
                    val proposedContent = coordinator.getProposedContent(toolUseId)
                    val diffTarget = if (currentContent == originalContent && proposedContent != null) {
                        proposedContent
                    } else {
                        currentContent
                    }
                    val reviewer = EditDiffReviewer(project)
                    val result = reviewer.review(filePath, originalContent, diffTarget)
                    // Update status synchronously — no invokeLater (fixes modality timing bug)
                    when (result.outcome) {
                        EditOutcome.ACCEPTED -> {
                            coordinator.recordDecision(toolUseId, EditOutcome.ACCEPTED)
                            browserRenderer.updateEditLinkStatus(toolUseId, "Accepted") { renderer.escapeHtml(it) }
                        }
                        EditOutcome.REJECTED -> {
                            reviewer.applyContent(filePath, originalContent)
                            coordinator.recordDecision(toolUseId, EditOutcome.REJECTED)
                            browserRenderer.updateEditLinkStatus(toolUseId, "Rejected") { renderer.escapeHtml(it) }
                        }
                        EditOutcome.MODIFIED -> {
                            val modifiedContent = result.modifiedContent ?: currentContent
                            reviewer.applyContent(filePath, modifiedContent)
                            coordinator.recordDecision(toolUseId, EditOutcome.MODIFIED, modifiedContent)
                            browserRenderer.updateEditLinkStatus(toolUseId, "Modified") { renderer.escapeHtml(it) }
                        }
                        EditOutcome.DISMISSED -> { /* X/Escape — no action */ }
                    }
                }
            } else if (editTimestamp != null) {
                // Tier 2: stale edit — captured content cleared, but timestamp available
                // Launch on Default dispatcher: reviewFromHistory calls git which cannot run on EDT
                scope.launch(Dispatchers.Default) {
                    val reviewer = EditDiffReviewer(project)
                    val historyResult = reviewer.reviewFromHistory(editTimestamp.filePath, editTimestamp.timestampMs)
                    if (historyResult == null) {
                        // Both Local History and git failed — fall through to unavailable
                        withContext(Dispatchers.Main) {
                            showNotification(
                                "Edit Review",
                                "No historical content found for this file. Local History may have been purged and the file may not be tracked by git.",
                                NotificationType.WARNING,
                            )
                            browserRenderer.markEditLinkUnavailable(toolUseId) { renderer.escapeHtml(it) }
                        }
                        return@launch
                    }
                    // reviewFromHistory opens a dialog (switches to EDT internally), so
                    // we're back on Default here. Switch to Main for UI updates.
                    withContext(Dispatchers.Main) {
                        when (historyResult.outcome) {
                            EditOutcome.ACCEPTED -> {
                                browserRenderer.updateEditLinkStatus(toolUseId, "Accepted") { renderer.escapeHtml(it) }
                            }
                            EditOutcome.REJECTED -> {
                                reviewer.applyContent(editTimestamp.filePath, historyResult.originalContent)
                                browserRenderer.updateEditLinkStatus(toolUseId, "Rejected") { renderer.escapeHtml(it) }
                                val feedback = EditDiffReviewer.buildFeedbackMessage(
                                    EditOutcome.REJECTED, editTimestamp.filePath,
                                    historyResult.originalContent, null,
                                )
                                if (feedback != null && bridge.isRunning) {
                                    bridge.sendMessage("[Edit Review]\n$feedback\nPlease continue with this change in mind.")
                                }
                            }
                            EditOutcome.MODIFIED -> {
                                val modifiedContent = historyResult.modifiedContent
                                    ?: java.io.File(editTimestamp.filePath).let { if (it.exists()) it.readText() else "" }
                                reviewer.applyContent(editTimestamp.filePath, modifiedContent)
                                browserRenderer.updateEditLinkStatus(toolUseId, "Modified") { renderer.escapeHtml(it) }
                                val feedback = EditDiffReviewer.buildFeedbackMessage(
                                    EditOutcome.MODIFIED, editTimestamp.filePath,
                                    historyResult.originalContent, modifiedContent,
                                )
                                if (feedback != null && bridge.isRunning) {
                                    bridge.sendMessage("[Edit Review]\n$feedback\nPlease continue with this change in mind.")
                                }
                            }
                            EditOutcome.DISMISSED -> { /* X/Escape — no action */ }
                        }
                    }
                }
            } else {
                // Tier 3: no data at all (e.g., plugin restart)
                ApplicationManager.getApplication().invokeLater {
                    showNotification(
                        "Edit Review",
                        "Edit data not available for this tool use. The edit may have occurred in a previous session.",
                        NotificationType.WARNING,
                    )
                    browserRenderer.markEditLinkUnavailable(toolUseId) { renderer.escapeHtml(it) }
                }
            }
            JBCefJSQuery.Response("ok")
        }
    }

    /**
     * Registers the handler for Accept/Reject button clicks from the JCEF chat panel.
     */
    fun setupEditActionHandler(editActionQuery: JBCefJSQuery) {
        editActionQuery.addHandler { payload ->
            val parts = payload.split(":", limit = 2)
            if (parts.size == 2) {
                val toolUseId = parts[0]
                val action = parts[1]
                handleEditAction(toolUseId, action)
            }
            JBCefJSQuery.Response("ok")
        }
    }

    /**
     * Processes an accept or reject action for a specific tool use.
     */
    fun handleEditAction(toolUseId: String, action: String) {
        val filePath = coordinator.getCapturedFilePath(toolUseId)
        val originalContent = coordinator.getOriginalContent(toolUseId)

        if (filePath == null || originalContent == null) {
            showNotification(
                "Edit Review",
                "Edit data not available for this tool use. The edit may have occurred in a previous session.",
                NotificationType.WARNING,
            )
            browserRenderer.markEditLinkUnavailable(toolUseId) { renderer.escapeHtml(it) }
            return
        }

        when (action) {
            "accept" -> {
                coordinator.recordDecision(toolUseId, EditOutcome.ACCEPTED)
                browserRenderer.updateEditLinkStatus(toolUseId, "Accepted") { renderer.escapeHtml(it) }
            }
            "reject" -> {
                val reviewer = EditDiffReviewer(project)
                reviewer.applyContent(filePath, originalContent)
                coordinator.recordDecision(toolUseId, EditOutcome.REJECTED)
                browserRenderer.updateEditLinkStatus(toolUseId, "Rejected") { renderer.escapeHtml(it) }
            }
        }
    }

    /**
     * Shows an IntelliJ notification balloon.
     */
    private fun showNotification(title: String, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ClawDEA")
            .createNotification(title, message, type)
            .notify(project)
    }
}
