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
 * interactive terminal handoff, and the /seed-wiki suggestion when
 * CLAUDE.md or the project wiki are missing.
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

    fun suggestSeedWikiIfMissing() {
        val basePath = project.basePath ?: return
        val claudeMdMissing = !java.io.File(basePath, "CLAUDE.md").exists()
        val wikiIndexMissing = try {
            val wikiDir = com.adobe.clawdea.knowledge.wiki.WikiLocator.getInstance(project).wikiDir()
            !java.nio.file.Files.exists(wikiDir.resolve("index.md"))
        } catch (_: Throwable) {
            // WikiLocator threw — treat as missing so we still nudge.
            true
        }
        val msg = seedWikiSuggestionMessage(claudeMdMissing, wikiIndexMissing) ?: return
        browserRenderer.appendHtml(renderer.renderInfoMessage(msg))
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

        for (html in renderHistory(history)) {
            browserRenderer.appendHtml(html)
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
                for (html in renderHistory(history)) {
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
     * Translate parsed history entries into the HTML fragments the chat
     * browser expects, pairing each `ToolUse` with its eventual `ToolResult`
     * so the resumed view matches what the live event stream produced when
     * the conversation originally happened.
     *
     * Iteration is index-based because `ToolResult` entries come AFTER the
     * matching `ToolUse` in the JSONL — we look ahead for the first match
     * and skip orphan results (CC sometimes writes tool_result envelopes
     * for tools that were silently cancelled).
     */
    internal fun renderHistory(history: List<HistoryEntry>): List<String> {
        val out = mutableListOf<String>()
        val consumedResults = mutableSetOf<Int>()
        for ((i, entry) in history.withIndex()) {
            when (entry) {
                is HistoryEntry.UserMessage -> out.add(renderer.renderUserMessage(entry.text))
                is HistoryEntry.AssistantText -> out.add(renderer.renderAssistantText(entry.text))
                is HistoryEntry.ToolUse -> {
                    val (resultContent, resultIsError, resultIdx) = findToolResult(history, i, entry.id)
                    if (resultIdx != -1) consumedResults.add(resultIdx)
                    val html = renderer.renderToolUseFromHistory(
                        toolName = entry.name,
                        input = entry.input,
                        toolUseId = entry.id,
                        resultContent = resultContent,
                        isError = resultIsError,
                    )
                    if (html.isNotBlank()) out.add(html)
                }
                is HistoryEntry.ToolResult -> {
                    // Already inlined under its tool block; otherwise drop —
                    // orphan results are noise (no matching tool_use to anchor to).
                }
            }
        }
        // Defensive sanity check; keeps unused-but-set warnings quiet.
        if (consumedResults.isEmpty() && history.any { it is HistoryEntry.ToolResult }) {
            log.debug("renderHistory: ${history.count { it is HistoryEntry.ToolResult }} tool_results had no matching tool_use")
        }
        return out
    }

    private fun findToolResult(
        history: List<HistoryEntry>,
        startIdx: Int,
        toolUseId: String,
    ): Triple<String?, Boolean, Int> {
        if (toolUseId.isBlank()) return Triple(null, false, -1)
        for (j in startIdx + 1 until history.size) {
            val candidate = history[j]
            if (candidate is HistoryEntry.ToolResult && candidate.toolUseId == toolUseId) {
                return Triple(candidate.content, candidate.isError, j)
            }
        }
        return Triple(null, false, -1)
    }

    /**
     * Called when the heartbeat detects a wall-clock gap big enough to indicate
     * a sleep/wake cycle.
     */
    fun onWakeDetected() {
        // The JS-based health probe passes even when JCEF's rendering
        // pipeline is frozen (JS engine ≠ compositor), so we can't rely on
        // it as a gate. Two-step recovery (issue #36):
        //   1. Kick the CEF compositor so post-wake JS-driven appends can
        //      actually paint. This is the part that was missing — without
        //      it, /refresh-view shows a fresh frame once but goes stale
        //      again the moment new content arrives.
        //   2. Reload the page with the latest history so the chat reflects
        //      anything that happened during the suspend window.
        log.info("view-health: wake detected, kicking compositor and reloading chat view")
        browserRenderer.forceRedraw()
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

    companion object {
        /**
         * Pure helper for the /seed-wiki suggestion logic — returns the message
         * to display, or null when both CLAUDE.md and the wiki index are
         * present. Extracted so the three-branch decision is unit-testable
         * without standing up the full SessionManager (which needs project,
         * bridge, renderers, etc.).
         */
        internal fun seedWikiSuggestionMessage(
            claudeMdMissing: Boolean,
            wikiIndexMissing: Boolean,
        ): String? {
            if (!claudeMdMissing && !wikiIndexMissing) return null
            return when {
                claudeMdMissing && wikiIndexMissing ->
                    "No CLAUDE.md or wiki found in this project. Type /seed-wiki to bootstrap both."
                claudeMdMissing ->
                    "No CLAUDE.md found in this project. Type /seed-wiki to bootstrap CLAUDE.md and refresh the wiki."
                else ->
                    "No wiki found in this project. Type /seed-wiki to bootstrap the wiki for Claude."
            }
        }
    }
}
