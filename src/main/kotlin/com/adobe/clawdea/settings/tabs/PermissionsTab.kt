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
// src/main/kotlin/com/adobe/clawdea/settings/tabs/PermissionsTab.kt
package com.adobe.clawdea.settings.tabs

import com.adobe.clawdea.settings.ClawDEASettings
import com.adobe.clawdea.settings.ToolApprovalModeUi
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Permissions section: tool-approval policy and auto-accept edits. These are
 * global settings (single [ClawDEASettings.State] scalars, not per-provider),
 * so they live in their own tab rather than under a specific provider.
 */
class PermissionsTab : SettingsTab {

    override val title: String = "Permissions"

    val toolApprovalCombo = ComboBox(ToolApprovalModeUi.comboBoxModel()).apply {
        toolTipText = ToolApprovalModeUi.TOOLTIP_TEXT
        ToolApprovalModeUi.installRenderer(this)
    }
    val autoAcceptEditsCheckbox = JBCheckBox("Auto-accept file edits (still reversible from the chat diff link)", false)

    override val component: JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("Tool approval:"), toolApprovalCombo, 1, false)
        .addComponent(autoAcceptEditsCheckbox, 1)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun loadFrom(state: ClawDEASettings.State) {
        toolApprovalCombo.selectedIndex = ToolApprovalModeUi.indexForKey(state.toolApprovalMode)
        autoAcceptEditsCheckbox.isSelected = state.autoAcceptEdits
    }

    override fun applyTo(state: ClawDEASettings.State) {
        state.toolApprovalMode = ToolApprovalModeUi.keyForIndex(toolApprovalCombo.selectedIndex)
        state.autoAcceptEdits = autoAcceptEditsCheckbox.isSelected
    }

    override fun isModifiedFrom(state: ClawDEASettings.State): Boolean =
        ToolApprovalModeUi.keyForIndex(toolApprovalCombo.selectedIndex) != state.toolApprovalMode ||
            autoAcceptEditsCheckbox.isSelected != state.autoAcceptEdits
}
