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
// src/main/kotlin/com/adobe/clawdea/settings/tabs/AdvancedTab.kt
package com.adobe.clawdea.settings.tabs

import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/** Advanced section extracted from the flat settings panel. */
class AdvancedTab : SettingsTab {

    override val title: String = "Advanced"

    val completionTokenBudgetField = JBTextField("2048", 6)
    val chatTokenBudgetField = JBTextField("16384", 6)
    val actionTokenBudgetField = JBTextField("4096", 6)
    val agentMaxToolRoundsField = JBTextField("0", 6)
    val agentMaxElapsedMinutesField = JBTextField("0", 6)
    val agentCompactionThresholdField = JBTextField("0.8", 6)
    val cliExtraArgsField = JBTextField("", 30)
    val cliEnvScriptField = JBTextField("", 30)
    val enablePsiCollectorCheckbox = JBCheckBox("Enable PSI semantic context", true)
    val enableGitCollectorCheckbox = JBCheckBox("Enable Git context", true)
    val preloadSkillCatalogCheckbox = JBCheckBox("Preload skill catalog into system prompt", true)
    val enableBaselineDefaultsCheckbox = JBCheckBox("Inject baseline working defaults into system prompt", true)
    val gatewayBareModeCheckbox = JBCheckBox(
        "Use minimal-mode CLI for completions (--bare; requires API-key auth)",
        true,
    )
    
    val completionsEnabledCheckbox = JBCheckBox("Enable inline completions", true)
    private val COMPLETION_MODELS = arrayOf("Sonnet", "Haiku")
    val completionsModelCombo = ComboBox<String>(COMPLETION_MODELS).apply {
        selectedIndex = 0
    }
    val completionsDebounceField = JBTextField("300", 6)
    val completionsManualOnlyCheckbox = JBCheckBox("Only request completions on hotkey (Trigger Inline Completion, default Alt+\\)", false).apply {
        toolTipText = "When on, completions never fire automatically as you type — they are requested only when you invoke the \"Trigger Inline Completion\" action. Rebind the hotkey in Settings → Keymap."
    }

    override val component: JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("Completion token budget:"), completionTokenBudgetField, 1, false)
        .addLabeledComponent(JBLabel("Chat token budget:"), chatTokenBudgetField, 1, false)
        .addLabeledComponent(JBLabel("Action token budget:"), actionTokenBudgetField, 1, false)
        .addLabeledComponent(JBLabel("Agent tool-call rounds before checkpoint (0 = unlimited):"), agentMaxToolRoundsField, 1, false)
        .addLabeledComponent(JBLabel("Agent minutes before checkpoint (0 = unlimited):"), agentMaxElapsedMinutesField, 1, false)
        .addLabeledComponent(JBLabel("Compact context at fraction of budget (0.0-1.0):"), agentCompactionThresholdField, 1, false)
        .addLabeledComponent(JBLabel("CLI extra args:"), cliExtraArgsField, 1, false)
        .addLabeledComponent(JBLabel("CLI env script:"), cliEnvScriptField, 1, false)
        .addComponent(enablePsiCollectorCheckbox, 1)
        .addComponent(enableGitCollectorCheckbox, 1)
        .addComponent(preloadSkillCatalogCheckbox, 1)
        .addComponent(enableBaselineDefaultsCheckbox, 1)
        .addComponent(gatewayBareModeCheckbox, 1)
        .addComponent(completionsEnabledCheckbox, 1)
        .addLabeledComponent(JBLabel("Completions model:"), completionsModelCombo, 1, false)
        .addLabeledComponent(JBLabel("Completions debounce (ms):"), completionsDebounceField, 1, false)
        .addComponent(completionsManualOnlyCheckbox, 1)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun loadFrom(state: ClawDEASettings.State) {
        completionTokenBudgetField.text = state.completionTokenBudget.toString()
        chatTokenBudgetField.text = state.chatTokenBudget.toString()
        actionTokenBudgetField.text = state.actionTokenBudget.toString()
        agentMaxToolRoundsField.text = state.agentMaxToolRounds.toString()
        agentMaxElapsedMinutesField.text = state.agentMaxElapsedMinutes.toString()
        agentCompactionThresholdField.text = state.agentContextCompactionThreshold.toString()
        cliExtraArgsField.text = state.cliExtraArgs
        cliEnvScriptField.text = state.cliEnvScript
        enablePsiCollectorCheckbox.isSelected = state.enablePsiCollector
        enableGitCollectorCheckbox.isSelected = state.enableGitCollector
        preloadSkillCatalogCheckbox.isSelected = state.preloadSkillCatalog
        enableBaselineDefaultsCheckbox.isSelected = state.enableBaselineDefaults
        gatewayBareModeCheckbox.isSelected = state.gatewayBareMode
        completionsEnabledCheckbox.isSelected = state.completionsEnabled
        completionsDebounceField.text = state.completionsDebounceMs.toString()
        completionsManualOnlyCheckbox.isSelected = state.completionsManualOnly
    }

    override fun applyTo(state: ClawDEASettings.State) {
        state.completionTokenBudget = completionTokenBudgetField.text.toIntOrNull() ?: 2048
        state.chatTokenBudget = chatTokenBudgetField.text.toIntOrNull() ?: 16384
        state.actionTokenBudget = actionTokenBudgetField.text.toIntOrNull() ?: 4096
        state.agentMaxToolRounds = agentMaxToolRoundsField.text.toIntOrNull() ?: 0
        state.agentMaxElapsedMinutes = agentMaxElapsedMinutesField.text.toIntOrNull() ?: 0
        state.agentContextCompactionThreshold = agentCompactionThresholdField.text.toDoubleOrNull() ?: 0.8
        state.cliExtraArgs = cliExtraArgsField.text
        state.cliEnvScript = cliEnvScriptField.text
        state.enablePsiCollector = enablePsiCollectorCheckbox.isSelected
        state.enableGitCollector = enableGitCollectorCheckbox.isSelected
        state.preloadSkillCatalog = preloadSkillCatalogCheckbox.isSelected
        state.enableBaselineDefaults = enableBaselineDefaultsCheckbox.isSelected
        state.gatewayBareMode = gatewayBareModeCheckbox.isSelected
        state.completionsEnabled = completionsEnabledCheckbox.isSelected
        state.completionsDebounceMs = completionsDebounceField.text.toIntOrNull() ?: 300
        state.completionsManualOnly = completionsManualOnlyCheckbox.isSelected
    }

    override fun isModifiedFrom(state: ClawDEASettings.State): Boolean =
        completionTokenBudgetField.text != state.completionTokenBudget.toString() ||
            chatTokenBudgetField.text != state.chatTokenBudget.toString() ||
            actionTokenBudgetField.text != state.actionTokenBudget.toString() ||
            cliExtraArgsField.text != state.cliExtraArgs ||
            cliEnvScriptField.text != state.cliEnvScript ||
            enablePsiCollectorCheckbox.isSelected != state.enablePsiCollector ||
            enableGitCollectorCheckbox.isSelected != state.enableGitCollector ||
            preloadSkillCatalogCheckbox.isSelected != state.preloadSkillCatalog ||
            enableBaselineDefaultsCheckbox.isSelected != state.enableBaselineDefaults ||
            agentMaxToolRoundsField.text != state.agentMaxToolRounds.toString() ||
            agentMaxElapsedMinutesField.text != state.agentMaxElapsedMinutes.toString() ||
            agentCompactionThresholdField.text != state.agentContextCompactionThreshold.toString() ||
            gatewayBareModeCheckbox.isSelected != state.gatewayBareMode ||
            completionsEnabledCheckbox.isSelected != state.completionsEnabled ||
            completionsDebounceField.text != state.completionsDebounceMs.toString() ||
            completionsManualOnlyCheckbox.isSelected != state.completionsManualOnly
}
