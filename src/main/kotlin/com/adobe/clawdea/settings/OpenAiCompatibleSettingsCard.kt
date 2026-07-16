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

import com.adobe.clawdea.provider.ProviderRegistry
import com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore
import com.adobe.clawdea.provider.openai.profile.ProfileImportExport
import com.adobe.clawdea.provider.openai.profile.ProfileStore
import com.adobe.clawdea.provider.openai.profile.ProfileValidator
import com.adobe.clawdea.provider.openai.profile.ValidationResult
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel

class OpenAiCompatibleSettingsCard : Disposable {
    private val settings = ClawDEASettings.getInstance()
    private val profileStore = ProfileStore(settings)
    private val credentialStore = ProfileCredentialStore()

    private val profileCombo = ComboBox<String>()
    private val importButton = JButton("Import Profile")
    private val exportTemplateButton = JButton("Export Template")
    private val exportConfiguredButton = JButton("Export Configured")
    private val removeButton = JButton("Remove Profile")

    private val dynamicFieldsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))

    private val credentialStatusLabel = JBLabel("No profile selected").apply {
        foreground = java.awt.Color(166, 173, 200)
        font = font.deriveFont(11f)
    }
    private val connectButton = JButton("Connect")

    private val endpointOverrideField = JBTextField("", 30)
    private val endpointOverrideHint = JBLabel("Advanced: override the base URL for this profile.").apply {
        foreground = java.awt.Color(166, 173, 200)
        font = font.deriveFont(11f)
    }

    private val refreshModelsButton = JButton("Refresh Models")

    private val modelTableModel = OpenAiModelTableModel()
    private val modelTable = JBTable(modelTableModel)
    private val modelsSection: JPanel = run {
        val decorator = ToolbarDecorator.createDecorator(modelTable)
            .setAddAction { modelTableModel.addRow() }
            .setRemoveAction {
                val row = modelTable.selectedRow
                if (row >= 0) modelTableModel.removeRow(modelTable.convertRowIndexToModel(row))
            }
        decorator.createPanel().apply {
            preferredSize = Dimension(preferredSize.width, 160)
        }
    }

    val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("Profile:"), profileRow(), 1, false)
        .addComponent(dynamicFieldsPanel, 1)
        .addVerticalGap(8)
        .addComponent(JBLabel("Credentials"), 1)
        .addComponent(credentialStatusLabel, 2)
        .addComponent(connectButton, 2)
        .addVerticalGap(8)
        .addComponent(JBLabel("Advanced"), 1)
        .addLabeledComponent(JBLabel("Base URL override:"), endpointOverrideField, 1, false)
        .addComponent(endpointOverrideHint, 2)
        .addVerticalGap(8)
        .addComponent(JBLabel("Models"), 1)
        .addComponent(refreshModelsButton, 1)
        .addComponent(modelsSection, 1)
        .panel

    private val dynamicTextFields = mutableMapOf<String, JBTextField>()

    init {
        importButton.addActionListener { doImport() }
        exportTemplateButton.addActionListener { doExportTemplate() }
        exportConfiguredButton.addActionListener { doExportConfigured() }
        removeButton.addActionListener { doRemove() }
        connectButton.addActionListener { doConnect() }
        refreshModelsButton.addActionListener { doRefreshModels() }

        profileCombo.addActionListener {
            onProfileSelected()
        }

        rebuildProfileList()
    }

    private fun profileRow(): JPanel = JPanel().apply {
        add(profileCombo)
        add(importButton)
        add(exportTemplateButton)
        add(exportConfiguredButton)
        add(removeButton)
    }

    private fun rebuildProfileList() {
        val profiles = profileStore.profiles()
        val items = profiles.map { it.name }.toTypedArray()
        profileCombo.model = DefaultComboBoxModel(items)

        val activeId = settings.state.activeOpenAiCompatibleProfileId
        if (activeId.isNotBlank()) {
            val activeProfile = profileStore.profile(activeId)
            if (activeProfile != null) {
                val index = profiles.indexOfFirst { it.id == activeId }
                if (index >= 0) {
                    profileCombo.selectedIndex = index
                }
            }
        }
        onProfileSelected()
    }

    private fun selectedProfile() = profileStore.profiles().getOrNull(profileCombo.selectedIndex)

    private fun onProfileSelected() {
        val profile = selectedProfile()
        if (profile == null) {
            credentialStatusLabel.text = "No profile selected"
            connectButton.isEnabled = false
            exportTemplateButton.isEnabled = false
            exportConfiguredButton.isEnabled = false
            removeButton.isEnabled = false
            endpointOverrideField.isEnabled = false
            refreshModelsButton.isEnabled = false
            rebuildDynamicFields(emptyList())
            return
        }

        settings.state.activeOpenAiCompatibleProfileId = profile.id

        val hasCredential = credentialStore.get(profile.id).isNotEmpty()
        credentialStatusLabel.text = if (hasCredential) "Credentials stored" else "No credentials"
        connectButton.isEnabled = true
        exportTemplateButton.isEnabled = true
        exportConfiguredButton.isEnabled = true
        removeButton.isEnabled = true
        endpointOverrideField.isEnabled = true
        refreshModelsButton.isEnabled = true

        rebuildDynamicFields(profile.settings)
        populateDynamicFields(profile, settings.state)

        val override = settings.state.openAiEndpointOverrides[profile.id].orEmpty()
        endpointOverrideField.text = override

        loadModelCatalogForProfile(profile.id)
    }

    private fun rebuildDynamicFields(settings: List<com.adobe.clawdea.provider.openai.profile.ProfileSetting>) {
        dynamicFieldsPanel.removeAll()
        dynamicTextFields.clear()

        if (settings.isEmpty()) {
            dynamicFieldsPanel.revalidate()
            dynamicFieldsPanel.repaint()
            return
        }

        val builder = FormBuilder.createFormBuilder()
        settings.forEach { setting ->
            val field = JBTextField("", 20)
            dynamicTextFields[setting.id] = field
            builder.addLabeledComponent(JBLabel("${setting.label}:"), field, 1, false)
        }
        dynamicFieldsPanel.add(builder.panel)
        dynamicFieldsPanel.revalidate()
        dynamicFieldsPanel.repaint()
    }

    private fun doImport() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        descriptor.title = "Select Profile JSON"
        val chooser = FileChooserFactory.getInstance().createFileChooser(descriptor, null, null)
        val files = chooser.choose(null)
        if (files.isEmpty()) return

        val file = File(files[0].path)
        val json = file.readText()

        when (val result = ProfileValidator.parseAndValidate(json, allowLocalHttp = false)) {
            is ValidationResult.Invalid -> {
                val message = result.diagnostics.joinToString("\n") { "${it.path}: ${it.message}" }
                Messages.showErrorDialog("Profile validation failed:\n$message", "Import Failed")
            }
            is ValidationResult.Valid -> {
                val dialog = ProfileImportPreviewDialog(result.preview)
                if (dialog.showAndGet()) {
                    profileStore.importValidated(result.profile)
                    rebuildProfileList()
                    Messages.showInfoMessage("Profile imported successfully.", "Import Succeeded")
                }
            }
        }
    }

    private fun doExportTemplate() {
        val profile = selectedProfile() ?: return
        val json = ProfileImportExport.exportTemplate(profile)
        val descriptor = FileSaverDescriptor("Export Profile Template", "Save profile template JSON")
        val saver = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, null)
        val wrapper = saver.save(null as com.intellij.openapi.vfs.VirtualFile?, "${profile.id}-template.json")
        if (wrapper != null) {
            File(wrapper.file.path).writeText(json)
        }
    }

    private fun doExportConfigured() {
        val profile = selectedProfile() ?: return
        val values = captureConfiguredValues()
        val json = ProfileImportExport.exportConfigured(profile, values)
        val descriptor = FileSaverDescriptor("Export Configured Profile", "Save configured profile JSON")
        val saver = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, null)
        val wrapper = saver.save(null as com.intellij.openapi.vfs.VirtualFile?, "${profile.id}-configured.json")
        if (wrapper != null) {
            File(wrapper.file.path).writeText(json)
        }
    }

    private fun doRemove() {
        val profile = selectedProfile() ?: return
        val choice = Messages.showYesNoDialog(
            "Remove profile '${profile.name}'? This will clear stored credentials and settings.",
            "Remove Profile",
            Messages.getQuestionIcon(),
        )
        if (choice == Messages.YES) {
            profileStore.remove(profile.id)
            credentialStore.clear(profile.id)
            rebuildProfileList()
        }
    }

    private fun doConnect() {
        val profile = selectedProfile() ?: return
        Messages.showInfoMessage(
            "Credential flow execution is not yet wired in the settings UI (will be added in Phase 2).",
            "Connect",
        )
    }

    private fun doRefreshModels() {
        val profile = selectedProfile() ?: return
        Messages.showInfoMessage(
            "Model refresh via HTTP is not yet wired in the settings UI (will be added in Phase 2).",
            "Refresh Models",
        )
    }

    private fun populateDynamicFields(profile: com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile, state: ClawDEASettings.State) {
        val persistedMap = state.openAiProfileValues
            .filterKeys { it.startsWith("${profile.id}|") }
            .mapKeys { it.key.removePrefix("${profile.id}|") }

        val model = OpenAiCompatibleSettingsModel(profile, System.getenv())
        val snapshot = model.load(persistedMap)

        snapshot.configuredValues.forEach { (id, value) ->
            dynamicTextFields[id]?.text = value
        }
    }

    private fun captureConfiguredValues(): Map<String, String> {
        val values = mutableMapOf<String, String>()
        dynamicTextFields.forEach { (id, field) ->
            values[id] = field.text
        }
        return values
    }

    private fun loadModelCatalogForProfile(profileId: String) {
        val catalogKey = ProviderRegistry.catalogKey(ProviderRegistry.OPENAI_COMPATIBLE_ID, profileId)
        val catalog = settings.state.modelCatalogs[catalogKey] ?: emptyList()
        modelTableModel.replaceAll(catalog)
    }

    fun load(state: ClawDEASettings.State) {
        rebuildProfileList()
        val profile = selectedProfile()
        if (profile != null) {
            populateDynamicFields(profile, state)
            endpointOverrideField.text = state.openAiEndpointOverrides[profile.id].orEmpty()
            loadModelCatalogForProfile(profile.id)
        }
    }

    fun isModified(state: ClawDEASettings.State): Boolean {
        val profile = selectedProfile() ?: return false

        val persistedMap = state.openAiProfileValues
            .filterKeys { it.startsWith("${profile.id}|") }
            .mapKeys { it.key.removePrefix("${profile.id}|") }

        val model = OpenAiCompatibleSettingsModel(profile, System.getenv())
        val snapshot = model.load(persistedMap)

        val currentValues = mutableMapOf<String, String>()
        dynamicTextFields.forEach { (id, field) ->
            currentValues[id] = field.text
        }
        val currentEndpoint = endpointOverrideField.text

        return model.isModified(snapshot, currentValues, currentEndpoint)
    }

    fun apply(state: ClawDEASettings.State) {
        val profile = selectedProfile() ?: return

        state.activeOpenAiCompatibleProfileId = profile.id

        profile.settings.forEach { setting ->
            val value = dynamicTextFields[setting.id]?.text ?: setting.defaultValue
            state.openAiProfileValues["${profile.id}|${setting.id}"] = value
        }

        val override = endpointOverrideField.text.trim()
        if (override.isNotBlank()) {
            state.openAiEndpointOverrides[profile.id] = override
        } else {
            state.openAiEndpointOverrides.remove(profile.id)
        }
    }

    fun saveModels(): MutableList<com.adobe.clawdea.gateway.ModelEntry> {
        return modelTableModel.rows.map { it.copy() }.toMutableList()
    }

    fun loadModels(catalog: List<com.adobe.clawdea.gateway.ModelEntry>) {
        modelTableModel.replaceAll(catalog)
    }

    fun isModelsModified(savedCatalog: List<com.adobe.clawdea.gateway.ModelEntry>): Boolean {
        return modelTableModel.rows != savedCatalog
    }

    override fun dispose() {
        // No resources to dispose
    }
}
