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

import com.adobe.clawdea.provider.openai.profile.ImportPreview
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class ProfileImportPreviewDialog(
    private val preview: ImportPreview,
) : DialogWrapper(null) {

    init {
        title = "Import Profile: ${preview.name}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val hostsText = if (preview.hosts.isNotEmpty()) {
            preview.hosts.joinToString(", ")
        } else {
            "(none)"
        }

        val credsText = if (preview.credentialInputs.isNotEmpty()) {
            preview.credentialInputs.joinToString(", ") { input ->
                "${input.label} (${if (input.secret) "secret" else "text"})"
            }
        } else {
            "(none)"
        }

        val envText = if (preview.environmentVariables.isNotEmpty()) {
            preview.environmentVariables.joinToString(", ")
        } else {
            "(none)"
        }

        val settingsText = if (preview.settings.isNotEmpty()) {
            preview.settings.joinToString(", ") { it.label }
        } else {
            "(none)"
        }

        val descriptionLabel = JBLabel("<html>${preview.description}</html>").apply {
            preferredSize = Dimension(400, 40)
        }

        val panel: JPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Description:"), descriptionLabel, 1, false)
            .addVerticalGap(8)
            .addLabeledComponent(JBLabel("Hosts:"), JBLabel(hostsText), 1, false)
            .addLabeledComponent(JBLabel("Credential inputs:"), JBLabel(credsText), 1, false)
            .addLabeledComponent(JBLabel("Environment variables:"), JBLabel(envText), 1, false)
            .addLabeledComponent(JBLabel("Settings:"), JBLabel(settingsText), 1, false)
            .panel

        return panel
    }
}
