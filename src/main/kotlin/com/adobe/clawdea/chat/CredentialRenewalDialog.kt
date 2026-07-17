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

import com.adobe.clawdea.provider.openai.auth.CredentialPromptResult
import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * EDT dialog that collects the profile-declared credential inputs when a credential must be renewed
 * (401/403). One field per [OpenAiCompatibleProfile.credentialFlow] input; a [JBPasswordField] when
 * the input is `secret`, otherwise a plain text field.
 *
 * [promptForCredentials] shows the dialog and returns a [CredentialPromptResult] with secret values
 * as `CharArray`s (caller must clear them), or null if the user cancelled. Password characters are
 * copied directly out of the Swing field so they never live in an intermediate String.
 */
class CredentialRenewalDialog(
    project: Project,
    private val profile: OpenAiCompatibleProfile,
) : DialogWrapper(project, true) {

    private val fields: Map<String, JTextField> = profile.credentialFlow.inputs.associate { input ->
        input.id to if (input.secret) JBPasswordField().apply { columns = 30 } else JBTextField(30)
    }

    init {
        title = "Renew credentials — ${profile.name}"
        setOKButtonText("Renew")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
        profile.credentialFlow.inputs.forEach { input ->
            val label = input.label.ifBlank { input.id }
            builder.addLabeledComponent("$label:", fields.getValue(input.id))
        }
        return builder.panel
    }

    override fun getPreferredFocusedComponent(): JComponent? =
        profile.credentialFlow.inputs.firstOrNull()?.let { fields[it.id] }

    /**
     * Show the dialog. Returns the collected inputs, or null if cancelled. Secret inputs are
     * returned as `CharArray`s; the caller is responsible for clearing them.
     */
    fun promptForCredentials(): CredentialPromptResult? {
        if (!showAndGet()) return null

        val secretInputs = mutableMapOf<String, CharArray>()
        val textInputs = mutableMapOf<String, String>()
        profile.credentialFlow.inputs.forEach { input ->
            val field = fields.getValue(input.id)
            if (input.secret && field is JBPasswordField) {
                secretInputs[input.id] = field.password
            } else {
                textInputs[input.id] = field.text
            }
        }
        return CredentialPromptResult(secretInputs = secretInputs, textInputs = textInputs)
    }
}
