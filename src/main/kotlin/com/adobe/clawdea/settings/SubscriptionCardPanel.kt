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
import com.adobe.clawdea.auth.SubscriptionAuth
import com.adobe.clawdea.auth.SubscriptionAuthEventListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Timer

class SubscriptionCardPanel : Disposable {
    private val statusLabel = JBLabel("Checking sign-in…").apply {
        font = font.deriveFont(12f)
    }
    private val hintLabel = JBLabel(
        "Your Claude subscription is used for chat, skills, and CLI features. " +
        "Inline completions require a separate Anthropic API key."
    ).apply {
        foreground = java.awt.Color(166, 173, 200)
        font = font.deriveFont(11f)
    }
    private val errorLabel = JBLabel("").apply {
        foreground = java.awt.Color(243, 139, 168)
        font = font.deriveFont(11f)
    }
    val signInButton = JButton("Sign in with Claude").apply { isVisible = false }
    val signOutButton = JButton("Sign out").apply { isVisible = false }
    val reauthButton = JButton("Re-authenticate").apply { isVisible = false }
    val refreshButton = JButton("Refresh status")

    val apiKeyField = JBPasswordField()
    private val apiKeyHint = JBLabel(
        "Optional — paste an Anthropic API key (sk-ant-…) to enable inline completions. " +
        "The subscription itself does not cover the completions API."
    ).apply {
        foreground = java.awt.Color(166, 173, 200)
        font = font.deriveFont(11f)
    }

    val panel: JPanel = FormBuilder.createFormBuilder()
        .addComponent(statusLabel, 0)
        .addComponent(buttonRow(), 1)
        .addComponent(hintLabel, 2)
        .addComponent(errorLabel, 2)
        .addLabeledComponent(JBLabel("API Key (completions):"), apiKeyField, 2, false)
        .addComponent(apiKeyHint, 2)
        .panel

    // Declared before `init` because the init block calls `refreshStatus()`,
    // which dereferences this token. Moving it below the init block leaves
    // it null during construction and throws NPE from the settings panel.
    private val inFlightToken = AtomicLong(0)

    init {
        signInButton.addActionListener { doSignIn() }
        signOutButton.addActionListener { doSignOut() }
        reauthButton.addActionListener { doSignIn() }
        refreshButton.addActionListener { refreshStatus() }

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(
            SubscriptionAuthEventListener.TOPIC,
            object : SubscriptionAuthEventListener {
                override fun onStatusChanged(status: AuthStatus) {
                    if (status is AuthStatus.SignedIn) {
                        // Sign-in just succeeded (or subscription detail filled in) —
                        // refresh the live model catalog so the dropdown reflects the
                        // models this tier can actually use.
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
                errorLabel.text = "Timed out waiting for `claude auth status`. Check the CLI path."
                render(AuthStatus.Unknown)
            }
        }.apply {
            isRepeats = false
            start()
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            SubscriptionAuth.getInstance().invalidateCache()
            val status = SubscriptionAuth.getInstance().getStatusBlocking()
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
        // The Invalid branch and the timeout path both set errorLabel, so render()
        // only clears it for states that have no associated error message.
        when (status) {
            AuthStatus.NotSignedIn -> {
                errorLabel.text = ""
                statusLabel.text = "Not signed in"
                signInButton.isVisible = true
                signOutButton.isVisible = false
                reauthButton.isVisible = false
            }
            is AuthStatus.SignedIn -> {
                errorLabel.text = ""
                val tier = status.tier?.let { " (${it})" } ?: ""
                val email = status.email?.let { " as $it" } ?: ""
                statusLabel.text = "Signed in${email}${tier}"
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
                statusLabel.text = "Unable to verify sign-in state — check the Claude CLI path."
                signInButton.isVisible = true
                signOutButton.isVisible = false
                reauthButton.isVisible = false
            }
        }
    }

    private fun doSignIn() {
        setBusy("Signing in… complete the browser flow; this will update automatically.")
        SubscriptionAuth.getInstance().signIn { status ->
            invokeOnEdt {
                val err = SubscriptionAuth.getInstance().lastSignInError()
                if (err != null) errorLabel.text = err.take(200)
                render(status)
            }
        }
    }

    private fun doSignOut() {
        setBusy("Signing out…")
        SubscriptionAuth.getInstance().signOut { status ->
            invokeOnEdt { render(status) }
        }
    }

    /**
     * EDT dispatch helper that uses [ModalityState.any] so updates still run
     * while the (modal) Settings dialog is on screen. The default modality
     * state of `invokeLater` is NON_MODAL, which queues the runnable until
     * the dialog closes — that's why the status label appeared to hang.
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
