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
package com.adobe.clawdea.settings

import com.adobe.clawdea.auth.AuthStatus
import com.adobe.clawdea.auth.CodexSubscriptionAuth
import com.adobe.clawdea.auth.CodexSubscriptionAuthEventListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Settings card for the OpenAI ChatGPT subscription (codex). Mirrors [SubscriptionCardPanel] but
 * has NO API-key field (inline completions are out of scope for the subscription provider) and is
 * wired to [CodexSubscriptionAuth] and the DISTINCT [CodexSubscriptionAuthEventListener.TOPIC], so
 * codex sign-in/out never flips the Claude subscription card.
 */
class OpenAiSubscriptionCardPanel : Disposable {
    private val statusLabel = JBLabel("Checking sign-in…").apply {
        font = font.deriveFont(12f)
    }
    private val hintLabel = JBLabel(
        "Your OpenAI ChatGPT subscription (via the codex CLI) is used for chat. " +
        "Sign in opens your browser to complete the flow."
    ).apply {
        foreground = java.awt.Color(166, 173, 200)
        font = font.deriveFont(11f)
    }
    private val errorLabel = JBLabel("").apply {
        foreground = java.awt.Color(243, 139, 168)
        font = font.deriveFont(11f)
    }
    val signInButton = JButton("Sign in with ChatGPT").apply { isVisible = false }
    val signOutButton = JButton("Sign out").apply { isVisible = false }
    val reauthButton = JButton("Re-authenticate").apply { isVisible = false }
    val refreshButton = JButton("Refresh status")

    val panel: JPanel = FormBuilder.createFormBuilder()
        .addComponent(statusLabel, 0)
        .addComponent(buttonRow(), 1)
        .addComponent(hintLabel, 2)
        .addComponent(errorLabel, 2)
        .panel

    // Declared before `init` because the init block calls `refreshStatus()`,
    // which dereferences this token.
    private val inFlightToken = AtomicLong(0)

    init {
        signInButton.addActionListener { doSignIn() }
        signOutButton.addActionListener { doSignOut() }
        reauthButton.addActionListener { doSignIn() }
        refreshButton.addActionListener { refreshStatus() }

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(
            CodexSubscriptionAuthEventListener.TOPIC,
            object : CodexSubscriptionAuthEventListener {
                override fun onStatusChanged(status: AuthStatus) {
                    if (status is AuthStatus.SignedIn) {
                        com.adobe.clawdea.gateway.ModelSelectorProbeStarter.runProbe()
                    }
                    invokeOnEdt { render(status) }
                }
                override fun onAuthFailed(reason: String) {
                    invokeOnEdt { refreshStatus() }
                }
            },
        )
        refreshStatus()
    }

    private fun buttonRow(): JPanel = JPanel().apply {
        add(signInButton)
        add(signOutButton)
        add(reauthButton)
        add(refreshButton)
    }

    private fun refreshStatus() {
        val token = inFlightToken.incrementAndGet()
        setBusy("Checking sign-in…")
        val timeoutTimer = Timer(REFRESH_UI_TIMEOUT_MS) {
            if (inFlightToken.get() == token) {
                errorLabel.text = "Timed out waiting for `codex login status`. Check the codex CLI path."
                render(AuthStatus.Unknown)
            }
        }.apply {
            isRepeats = false
            start()
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            CodexSubscriptionAuth.getInstance().invalidateCache()
            val status = CodexSubscriptionAuth.getInstance().getStatusBlocking()
            invokeOnEdt {
                if (inFlightToken.get() == token) {
                    timeoutTimer.stop()
                    render(status)
                }
            }
        }
    }

    private fun render(status: AuthStatus) {
        refreshButton.isEnabled = true
        when (status) {
            AuthStatus.NotSignedIn -> {
                errorLabel.text = ""
                statusLabel.text = "Not signed in"
                signInButton.isVisible = true
                signOutButton.isVisible = false
                reauthButton.isVisible = false
            }
            is AuthStatus.SignedIn -> {
                // `codex login status` carries no email/tier, so we just report signed-in.
                errorLabel.text = ""
                statusLabel.text = "Signed in with ChatGPT"
                signInButton.isVisible = false
                signOutButton.isVisible = true
                reauthButton.isVisible = false
            }
            is AuthStatus.Invalid -> {
                statusLabel.text = "Credentials invalid — please re-authenticate"
                signInButton.isVisible = false
                signOutButton.isVisible = true
                reauthButton.isVisible = true
                errorLabel.text = status.reason.take(200)
            }
            AuthStatus.Unknown -> {
                statusLabel.text = "Unable to verify sign-in state — check the codex CLI path."
                signInButton.isVisible = true
                signOutButton.isVisible = false
                reauthButton.isVisible = false
            }
        }
    }

    private fun doSignIn() {
        setBusy("Signing in… complete the browser flow; this will update automatically.")
        CodexSubscriptionAuth.getInstance().signIn { status ->
            invokeOnEdt {
                val err = CodexSubscriptionAuth.getInstance().lastSignInError()
                if (err != null) errorLabel.text = err.take(200)
                render(status)
            }
        }
    }

    private fun doSignOut() {
        setBusy("Signing out…")
        CodexSubscriptionAuth.getInstance().signOut { status ->
            invokeOnEdt { render(status) }
        }
    }

    /**
     * EDT dispatch helper using [ModalityState.any] so updates still run while the
     * (modal) Settings dialog is on screen. See [SubscriptionCardPanel.invokeOnEdt].
     */
    private fun invokeOnEdt(runnable: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(runnable, ModalityState.any())
    }

    private fun setBusy(message: String) {
        statusLabel.text = message
        signInButton.isVisible = false
        signOutButton.isVisible = false
        reauthButton.isVisible = false
        refreshButton.isEnabled = false
        errorLabel.text = ""
    }

    companion object {
        private const val REFRESH_UI_TIMEOUT_MS: Int = 8_000
    }

    override fun dispose() {
        // Bus connection is automatically released via messageBus.connect(this).
    }
}
