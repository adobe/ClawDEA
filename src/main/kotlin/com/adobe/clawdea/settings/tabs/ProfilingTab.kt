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
// src/main/kotlin/com/adobe/clawdea/settings/tabs/ProfilingTab.kt
package com.adobe.clawdea.settings.tabs

import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

/** Profiling section extracted from the flat settings panel. */
class ProfilingTab : SettingsTab {

    override val title: String = "Profiling"

    private val BACKEND_OPTIONS = arrayOf("Auto", "IntelliJ Profiler", "JFR")
    private val BACKEND_KEYS = arrayOf("auto", "intellij", "jfr")
    val profilingBackendCombo = ComboBox(DefaultComboBoxModel(BACKEND_OPTIONS))
    val profilingSamplingIntervalField = JBTextField("10", 6)
    val profilingMaxDurationField = JBTextField("900", 6)
    val profilingMaxRecordingMbField = JBTextField("500", 6)
    val profilingStackDepthField = JBTextField("128", 6)
    val profilingMaxRecordingsField = JBTextField("20", 6)
    val profilingMaxStorageGbField = JBTextField("5", 6)
    val profilingAutoAnalyzeCheckbox = JBCheckBox("Auto-analyze after capture", true)
    val profilingTopNField = JBTextField("50", 6)

    override val component: JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("Backend:"), profilingBackendCombo, 1, false)
        .addLabeledComponent(JBLabel("Sampling interval (ms):"), profilingSamplingIntervalField, 1, false)
        .addLabeledComponent(JBLabel("Max duration (seconds):"), profilingMaxDurationField, 1, false)
        .addLabeledComponent(JBLabel("Max recording size (MB):"), profilingMaxRecordingMbField, 1, false)
        .addLabeledComponent(JBLabel("Stack depth:"), profilingStackDepthField, 1, false)
        .addLabeledComponent(JBLabel("Max stored recordings:"), profilingMaxRecordingsField, 1, false)
        .addLabeledComponent(JBLabel("Max storage (GB):"), profilingMaxStorageGbField, 1, false)
        .addComponent(profilingAutoAnalyzeCheckbox, 1)
        .addLabeledComponent(JBLabel("Top-N hotspots:"), profilingTopNField, 1, false)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun loadFrom(state: ClawDEASettings.State) {
        selectProfilingBackend(state.profilingBackendPreference)
        profilingSamplingIntervalField.text = state.profilingSamplingIntervalMs.toString()
        profilingMaxDurationField.text = state.profilingMaxDurationSeconds.toString()
        profilingMaxRecordingMbField.text = state.profilingMaxRecordingMb.toString()
        profilingStackDepthField.text = state.profilingStackDepth.toString()
        profilingMaxRecordingsField.text = state.profilingMaxRecordings.toString()
        profilingMaxStorageGbField.text = state.profilingMaxStorageGb.toString()
        profilingAutoAnalyzeCheckbox.isSelected = state.profilingAutoAnalyze
        profilingTopNField.text = state.profilingTopN.toString()
    }

    override fun applyTo(state: ClawDEASettings.State) {
        state.profilingBackendPreference = selectedProfilingBackendKey()
        state.profilingSamplingIntervalMs = profilingSamplingIntervalField.text.toIntOrNull() ?: 10
        state.profilingMaxDurationSeconds = profilingMaxDurationField.text.toIntOrNull() ?: 900
        state.profilingMaxRecordingMb = profilingMaxRecordingMbField.text.toIntOrNull() ?: 500
        state.profilingStackDepth = profilingStackDepthField.text.toIntOrNull() ?: 128
        state.profilingMaxRecordings = profilingMaxRecordingsField.text.toIntOrNull() ?: 20
        state.profilingMaxStorageGb = profilingMaxStorageGbField.text.toIntOrNull() ?: 5
        state.profilingAutoAnalyze = profilingAutoAnalyzeCheckbox.isSelected
        state.profilingTopN = profilingTopNField.text.toIntOrNull() ?: 50
    }

    override fun isModifiedFrom(state: ClawDEASettings.State): Boolean =
        selectedProfilingBackendKey() != state.profilingBackendPreference ||
            profilingSamplingIntervalField.text != state.profilingSamplingIntervalMs.toString() ||
            profilingMaxDurationField.text != state.profilingMaxDurationSeconds.toString() ||
            profilingMaxRecordingMbField.text != state.profilingMaxRecordingMb.toString() ||
            profilingStackDepthField.text != state.profilingStackDepth.toString() ||
            profilingMaxRecordingsField.text != state.profilingMaxRecordings.toString() ||
            profilingMaxStorageGbField.text != state.profilingMaxStorageGb.toString() ||
            profilingAutoAnalyzeCheckbox.isSelected != state.profilingAutoAnalyze ||
            profilingTopNField.text != state.profilingTopN.toString()

    private fun selectedProfilingBackendKey(): String {
        val idx = profilingBackendCombo.selectedIndex
        return if (idx >= 0) BACKEND_KEYS[idx] else "auto"
    }

    private fun selectProfilingBackend(key: String) {
        val idx = BACKEND_KEYS.indexOf(key)
        profilingBackendCombo.selectedIndex = if (idx >= 0) idx else 0
    }
}
