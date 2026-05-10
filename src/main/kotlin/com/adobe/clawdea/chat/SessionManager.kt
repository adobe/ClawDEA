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
package com.adobe.clawdea.chat

import com.adobe.clawdea.chat.session.HistoryEntry
import com.adobe.clawdea.chat.session.SessionPickerDialog
import com.adobe.clawdea.chat.session.SessionScanner
import com.adobe.clawdea.cli.CliBridge
import com.adobe.clawdea.skills.SkillInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import kotlinx.coroutines.*

/**
 * Manages session lifecycle: resume, reload-and-replay, wake recovery,
 * interactive terminal handoff, and CLAUDE.md init suggestion.
 *
 * Extracted from ChatPanel to keep session orchestration separate from
 * UI wiring and event handling.
 */
class SessionManager(
    private val project: Project,
    private val bridge: CliBridge,
    private val renderer: MessageRenderer,
    private val browserRenderer: ChatBrowserRenderer,
    private val turnController: TurnController,
    private val getDiscoveredSkills: () -> List<SkillInfo>,
    private val onResetUi: () -> Unit,
    private val onRestartAfterTerminal: (sessionId: String?) -> Unit,
) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(SessionManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun suggestInitIfMissingClaudeMd() {
        val basePath = project.basePath ?: return
        val claudeMd = java.io.File(basePath, "CLAUDE.md")
        if (claudeMd.exists()) return
        browserRenderer.appendHtml(renderer.renderInfoMessage(
            "No CLAUDE.md found in this project. Type /init to generate one with project context for Claude.",
        ))
    }

    /**
     * Resume a previous session by ID: reset turn state, clear chat, load
     * history into the view, and (re)start the bridge with that session.
     */
    fun resumeSession(
        sessionId: String,
        basePath: String,
        silentOnFailure: Boolean,
    ) {
        onResetUi()
        browserRenderer.clearMessages()

        val history = try {
            SessionScanner.loadHistory(basePath, sessionId)
        } catch (t: Throwable) {
            log.warn("resumeSession: history load failed for $sessionId", t)
            return
        }

        for (entry in history) {
            when (entry) {
                is HistoryEntry.UserMessage -> browserRenderer.appendHtml(renderer.renderUserMessage(entry.text))
                is HistoryEntry.AssistantText -> browserRenderer.appendHtml(renderer.renderAssistantText(entry.text))
                is HistoryEntry.ToolUse -> browserRenderer.appendHtml(renderer.renderToolUseCompact(entry.name, entry.input))
            }
        }

        bridge.stop()
        scope.launch {
            try {
                bridge.start(resumeSessionId = sessionId, skills = getDiscoveredSkills())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("resumeSession: bridge.start failed for $sessionId", e)
                if (!silentOnFailure) {
                    withContext(Dispatchers.Main) {
                        browserRenderer.appendHtml(renderer.renderError("Failed to resume session: ${e.message}"))
                    }
                }
            }
        }
    }

    /**
     * Entry point for auto-resume on IDE startup. Silent no-op if the project
     * has no base path; silent fallback to a fresh chat on any load/start failure.
     */
    fun requestAutoResume(sessionId: String) {
        val basePath = project.basePath ?: return
        resumeSession(sessionId, basePath, silentOnFailure = true)
    }

    /**
     * Rebuild the JCEF chat view and replay the current session's history.
     * Shared between wake-recovery (auto) and the /refresh-view slash command.
     * Must be called on the EDT.
     */
    fun reloadAndReplay(reason: String) {
        val basePath = project.basePath
        // bridge.sessionId may be a resumed-process ID that has no .jsonl file
        // (the history lives in the original session's file). Fall back to the
        // most recent session on disk when the bridge ID doesn't resolve.
        val sessionId = run {
            val bridgeId = bridge.sessionId
            if (bridgeId != null && basePath != null && SessionScanner.hasSessionFile(basePath, bridgeId)) {
                bridgeId
            } else {
                basePath?.let { SessionScanner.scan(it).firstOrNull()?.id }
            }
        }
        log.info("reloadAndReplay: reason=$reason, session=${sessionId ?: "<unknown>"}")

        val historyHtml = StringBuilder()
        if (basePath != null && sessionId != null) {
            try {
                val history = SessionScanner.loadHistory(basePath, sessionId)
                log.info("reloadAndReplay: loaded ${history.size} history entries")
                for (entry in history) {
                    val html = when (entry) {
                        is HistoryEntry.UserMessage -> renderer.renderUserMessage(entry.text)
                        is HistoryEntry.AssistantText -> renderer.renderAssistantText(entry.text)
                        is HistoryEntry.ToolUse -> renderer.renderToolUseCompact(entry.name, entry.input)
                    }
                    historyHtml.append(html)
                }
            } catch (t: Throwable) {
                log.warn("reloadAndReplay: history load failed", t)
            }
        }

        // Embed history directly in the page HTML so there's no race
        // between onLoadEnd draining pending HTML and the page being ready.
        browserRenderer.loadPage(historyHtml.toString())
    }

    /**
     * Called when the heartbeat detects a wall-clock gap big enough to indicate
     * a sleep/wake cycle.
     */
    fun onWakeDetected() {
        // Always reload — the JS-based health probe passes even when JCEF's
        // rendering pipeline is frozen (JS engine ≠ compositor).
        log.info("view-health: wake detected, reloading chat view")
        reloadAndReplay("wake-recovery")
    }

    /**
     * Show the session picker dialog and resume the selected session.
     */
    fun openResumeDialog(command: String) {
        val basePath = project.basePath
        if (basePath == null) {
            browserRenderer.appendHtml(renderer.renderError("Cannot determine project path for session lookup."))
            return
        }

        val sessions = SessionScanner.scan(basePath)
        if (sessions.isEmpty()) {
            browserRenderer.appendHtml(renderer.renderInfoMessage("No sessions found for this project."))
            return
        }

        val dialog = SessionPickerDialog(project, sessions)
        if (dialog.showAndGet()) {
            dialog.selectedSession?.let { selected ->
                resumeSession(selected.id, basePath, silentOnFailure = false)
            }
        }
    }

    /**
     * Open an interactive terminal dialog. Stops the bridge so the terminal
     * can own the CLI session, then restarts afterwards via the
     * [onRestartAfterTerminal] callback.
     */
    fun openInteractiveTerminal(command: String?) {
        // Stop bridge so the terminal can resume the same session
        val currentSessionId = bridge.sessionId
        bridge.stop()

        val infoMsg = if (command.isNullOrBlank()) "Opening Claude Code..." else "Running $command interactively..."
        browserRenderer.appendHtml(renderer.renderInfoMessage(infoMsg))
        val dialog = InteractiveCommandDialog(project, command, continueSessionId = currentSessionId)
        dialog.show()
        val msg = if (dialog.exitCode == DialogWrapper.OK_EXIT_CODE) {
            "Interactive command completed"
        } else {
            "Interactive command cancelled"
        }
        browserRenderer.appendHtml(renderer.renderInfoMessage(msg))
        // Restart bridge to pick up any config/auth changes from the interactive command
        onRestartAfterTerminal(currentSessionId)
    }
}
