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

import com.adobe.clawdea.provider.BackendKind
import com.adobe.clawdea.chat.session.SessionOrigin
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Explicit confirmation shown before a conversation is carried across providers (a provider
 * "fallback"). ClawDEA never silently switches providers on a remote error; a cross-provider
 * handoff only proceeds when the user affirmatively confirms it here.
 *
 * The dialog names the failed/source provider and the target provider, and states clearly that
 * plain user/assistant text is transferred as context while tool-protocol state (tool calls,
 * results, reasoning) is dropped — the alternate provider replays the transcript as text only.
 *
 * Only [showAndGet] returning `true` (the OK / "Continue" button) starts the replay.
 */
class ProviderFallbackPrompt(
    project: Project?,
    private val fromProvider: String,
    private val toProvider: String,
) : DialogWrapper(project, true) {

    init {
        title = "Continue this conversation with a different provider?"
        setOKButtonText("Continue")
        setCancelButtonText("Stay")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)

        panel.add(JLabel("This session started with “$fromProvider”."))
        panel.add(JLabel("Continuing it now uses “$toProvider” instead."))
        panel.add(JLabel(" "))
        panel.add(JLabel("Your and the assistant's message text will be carried over as context."))
        panel.add(JLabel("Tool calls, tool results, and reasoning are NOT transferred."))
        panel.add(JLabel(" "))
        panel.add(JLabel("Continue to replay the transcript under “$toProvider”, or Stay to keep the current provider."))
        return panel
    }

    companion object {
        /** Map a session's origin to the backend kind that produced it. */
        private fun backendKindFor(origin: SessionOrigin): BackendKind = when (origin) {
            SessionOrigin.CLAUDE -> BackendKind.CLAUDE_CLI
            SessionOrigin.CODEX -> BackendKind.CODEX_APP_SERVER
            SessionOrigin.OPENAI_COMPATIBLE -> BackendKind.OPENAI_COMPATIBLE_HTTP
        }

        /**
         * Decide whether resuming a session of [origin] under the currently active [activeKind] is a
         * cross-provider handoff that must be user-confirmed.
         *
         * Scoped to handoffs that involve the OpenAI-compatible HTTP backend (as source or target),
         * so pre-existing Claude/Codex switch behavior is left unchanged. A same-backend resume
         * (including Claude→Claude, Codex→Codex, and OpenAI→OpenAI) is never a fallback.
         */
        fun requiresConfirmation(origin: SessionOrigin, activeKind: BackendKind): Boolean {
            val originKind = backendKindFor(origin)
            if (originKind == activeKind) return false
            return originKind == BackendKind.OPENAI_COMPATIBLE_HTTP ||
                activeKind == BackendKind.OPENAI_COMPATIBLE_HTTP
        }
    }
}
