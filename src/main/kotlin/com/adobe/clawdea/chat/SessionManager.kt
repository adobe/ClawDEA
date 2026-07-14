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
import com.adobe.clawdea.chat.session.SessionCatalog
import com.adobe.clawdea.chat.session.SessionOrigin
import com.adobe.clawdea.chat.session.SessionPickerDialog
import com.adobe.clawdea.cli.CliBridge
import com.adobe.clawdea.cost.CostTracker
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
    /** Stable per-tab id for per-chat cost accounting. */
    private val chatId: String,
    private val getDiscoveredSkills: () -> List<SkillInfo>,
    private val onResetUi: () -> Unit,
    private val onRestartAfterTerminal: (sessionId: String?) -> Unit,
    /**
     * Recover the (possibly frozen) chat view after a detected sleep/wake gap.
     * Owned by [ChatPanel] because it must recreate the JBCefBrowser + swap the
     * Swing component (issue #36); the manager only decides *when* to trigger it.
     */
    private val onWakeRecovery: () -> Unit,
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
        val html = seedWikiSuggestionHtml(claudeMdMissing, wikiIndexMissing) ?: return
        browserRenderer.appendHtml(html)
    }

    /**
     * Resume a previous session by ID: reset turn state, clear chat, load
     * history into the view, and (re)start the bridge with that session.
     */
    fun resumeSession(
        sessionId: String,
        basePath: String,
        silentOnFailure: Boolean,
        origin: SessionOrigin = SessionCatalog.resolveOrigin(basePath, sessionId) ?: SessionOrigin.CLAUDE,
    ) {
        onResetUi()
        browserRenderer.clearMessages()

        val history = try {
            SessionCatalog.loadHistory(basePath, sessionId, origin)
        } catch (t: Throwable) {
            log.warn("resumeSession: history load failed for $sessionId", t)
            return
        }

        // Attribute historical bubbles to the agent that actually produced the transcript (its
        // origin), not the backend now driving the panel — a Claude transcript resumed under codex
        // still shows "Claude" for the replayed turns. Live turns reset this at send time.
        renderer.assistantLabel = origin.displayLabel
        for (html in renderHistory(history)) {
            browserRenderer.appendHtml(html)
        }

        // Cost/savings reconstruction reads Claude transcript files; only meaningful for Claude
        // origins. Codex cost seeding on resume is out of scope (no equivalent transcript reader).
        if (origin == SessionOrigin.CLAUDE) {
            val resumeCost = CostTracker.getInstance(project).seedFromResume(chatId, sessionId)
            if (resumeCost.totalUsd > 0) {
                // Mirror the live per-turn footer so a resumed session shows its
                // accumulated cost at the bottom of the chat, just like a live turn.
                // No elapsed time — this is reconstructed from history, not a live turn.
                browserRenderer.appendHtml(
                    renderer.renderCostInfo(resumeCost.lastModel, null, resumeCost.totalUsd, 0),
                )
            }
            val savingsReco = com.adobe.clawdea.cost.TranscriptSavingsReader.reconstructFile(
                com.adobe.clawdea.cost.TranscriptCostReader.sessionTranscriptFile(basePath, sessionId),
            )
            com.adobe.clawdea.cost.SavingsTracker.getInstance(project)
                .seedFromResume(chatId, savingsReco.band, savingsReco.turns)
        }
        bridge.stop()

        // Native resume only works when the running backend owns the session's store (Claude→Claude
        // via `--resume`, Codex→Codex via `exec resume`). Across backends the id is meaningless to
        // the other CLI, so instead of resuming we replay the transcript as first-turn context.
        val native = resumeIsNative(origin, bridge.usesCodexBackend)
        val resumeId = if (native) sessionId else null
        val replay = if (native) null else buildReplay(history, origin)
        if (!native) {
            log.info("resumeSession: ${origin.displayLabel} session $sessionId resumed under ${bridge.agentLabel}; replaying transcript as context")
            browserRenderer.appendHtml(
                renderer.renderInfoMessage(
                    "Continuing this ${origin.displayLabel} conversation with ${bridge.agentLabel}. The transcript above " +
                        "is carried over as context, so your next message picks up where it left off.",
                ),
            )
        }
        scope.launch {
            try {
                bridge.start(resumeSessionId = resumeId, skills = getDiscoveredSkills(), replayContext = replay)
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

    private fun buildReplay(history: List<HistoryEntry>, origin: SessionOrigin): String? =
        com.adobe.clawdea.chat.session.TranscriptReplay
            .serialize(history, origin.displayLabel)
            .takeIf { it.isNotBlank() }

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
            if (bridgeId != null && basePath != null && SessionCatalog.resolveOrigin(basePath, bridgeId) != null) {
                bridgeId
            } else {
                basePath?.let { SessionCatalog.mostRecent(it)?.id }
            }
        }
        log.info("reloadAndReplay: reason=$reason, session=${sessionId ?: "<unknown>"}")

        val historyHtml = StringBuilder()
        if (basePath != null && sessionId != null) {
            try {
                val origin = SessionCatalog.resolveOrigin(basePath, sessionId) ?: SessionOrigin.CLAUDE
                val history = SessionCatalog.loadHistory(basePath, sessionId, origin)
                log.info("reloadAndReplay: loaded ${history.size} history entries (origin=${origin.displayLabel})")
                // Attribute replayed bubbles to the transcript's origin agent.
                renderer.assistantLabel = origin.displayLabel
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
     * browser expects. Delegates to the pure [HistoryReplayRenderer], which
     * pairs each `ToolUse` with its eventual `ToolResult` and reconstructs
     * sub-agent (`Agent`) dispatches as collapsed cards.
     */
    internal fun renderHistory(history: List<HistoryEntry>): List<String> =
        com.adobe.clawdea.chat.session.HistoryReplayRenderer.render(history, renderer)

    /**
     * Called when the heartbeat detects a wall-clock gap big enough to indicate
     * a sleep/wake cycle.
     */
    fun onWakeDetected() {
        // The JS-based health probe passes even when JCEF's rendering pipeline
        // is frozen (JS engine ≠ compositor), so we can't rely on it as a gate.
        // Soft compositor kicks (notifyScreenInfoChanged / wasResized /
        // invalidate) don't recover a stuck OSR surface either — a page reload
        // paints one fresh frame and then freezes again the moment new content
        // arrives. The only reliable fix (issue #36) is to recreate the browser
        // and replay history into the fresh surface, which [onWakeRecovery]
        // (ChatPanel.recreateBrowserAndReplay) does.
        log.info("view-health: wake detected, recreating chat view")
        onWakeRecovery()
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

        val sessions = SessionCatalog.scanAll(basePath)
        if (sessions.isEmpty()) {
            browserRenderer.appendHtml(renderer.renderInfoMessage("No sessions found for this project."))
            return
        }

        val dialog = SessionPickerDialog(project, sessions)
        if (dialog.showAndGet()) {
            dialog.selectedSession?.let { selected ->
                resumeSession(selected.id, basePath, silentOnFailure = false, origin = selected.origin)
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
         * True when the running backend can natively resume a session of the given [origin]:
         * Claude backend ↔ Claude sessions, codex backend ↔ codex sessions. A mismatch means the
         * id is unusable by the active CLI, so the caller replays the transcript as context instead.
         */
        internal fun resumeIsNative(origin: SessionOrigin, usesCodexBackend: Boolean): Boolean =
            when (origin) {
                SessionOrigin.CODEX -> usesCodexBackend
                SessionOrigin.CLAUDE -> !usesCodexBackend
            }

        /**
         * Pure helper for the /seed-wiki suggestion — returns the full HTML
         * info-block to append, or null when both CLAUDE.md and the wiki
         * index are present. Renders `/seed-wiki` as a clickable link
         * (`data-action="run-slash-command"`) so the user can launch the
         * command with one click instead of typing it. Extracted so the
         * three-branch decision is unit-testable without standing up the
         * full SessionManager.
         */
        internal fun seedWikiSuggestionHtml(
            claudeMdMissing: Boolean,
            wikiIndexMissing: Boolean,
        ): String? {
            if (!claudeMdMissing && !wikiIndexMissing) return null
            val link = SEED_WIKI_LINK
            val body = when {
                claudeMdMissing && wikiIndexMissing ->
                    "No CLAUDE.md or wiki found in this project. Type $link to bootstrap both."
                claudeMdMissing ->
                    "No CLAUDE.md found in this project. Type $link to bootstrap CLAUDE.md and refresh the wiki."
                else ->
                    "No wiki found in this project. Type $link to bootstrap the wiki for Claude."
            }
            return """<div class="info-block">$body</div>"""
        }

        /** Stable HTML for the inline `/seed-wiki` slash-command link. */
        internal const val SEED_WIKI_LINK =
            """<a href="#" class="slash-command-link" data-action="run-slash-command" data-slash="/seed-wiki">/seed-wiki</a>"""
    }
}
