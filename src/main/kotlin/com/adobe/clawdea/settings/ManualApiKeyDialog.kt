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

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * Modal dialog that collects an API key to store directly in PasswordSafe, bypassing the profile's
 * declarative sign-in flow. Used by the settings card's "Set API Key Manually" escape hatch when the
 * credential flow fails server-side. The key is entered in a [JBPasswordField] and returned as a
 * [CharArray] so it never lives in an intermediate String; the caller zeroes it after use.
 */
class ManualApiKeyDialog(private val profileName: String) : DialogWrapper(true) {

    private val keyField = JBPasswordField().apply { columns = 40 }

    init {
        title = "Set API Key — $profileName"
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent("API key:", keyField)
            .addComponentToRightColumn(
                JBLabel("Stored in IntelliJ PasswordSafe for this profile. Use when Connect fails.").apply {
                    foreground = java.awt.Color(166, 173, 200)
                    font = font.deriveFont(11f)
                },
            )
            .panel

    override fun getPreferredFocusedComponent(): JComponent = keyField

    /**
     * Show the dialog. Returns the entered key as a [CharArray] (caller must clear it), or null if
     * the user cancelled or left the field blank.
     */
    fun promptForKey(): CharArray? {
        if (!showAndGet()) return null
        val chars = keyField.password
        if (chars.isEmpty() || chars.all { it == ' ' }) {
            chars.fill(' ')
            return null
        }
        return chars
    }
}
