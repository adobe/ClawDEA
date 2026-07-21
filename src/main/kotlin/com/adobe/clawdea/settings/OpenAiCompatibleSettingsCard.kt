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
    private val setKeyManuallyButton = JButton("Set API Key Manually…")

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
        .addComponent(credentialButtonsRow(), 2)
        .addComponent(
            JBLabel("Connect runs the profile's sign-in flow. If that fails (e.g. a server 500), " +
                "paste an API key directly with “Set API Key Manually”.").apply {
                foreground = java.awt.Color(166, 173, 200)
                font = font.deriveFont(11f)
            },
            2,
        )
        .addVerticalGap(8)
        .addComponent(JBLabel("Advanced"), 1)
        .addLabeledComponent(JBLabel("Base URL override:"), endpointOverrideField, 1, false)
        .addComponent(endpointOverrideHint, 2)
        .addVerticalGap(8)
        .addComponent(JBLabel("Models"), 1)
        .addComponent(refreshModelsButton, 1)
        .addComponent(
            JBLabel("Refresh fetches the provider's models and verifies which can call tools; " +
                "only tool-capable models can start agentic chat.").apply {
                foreground = java.awt.Color(166, 173, 200)
                font = font.deriveFont(11f)
            },
            2,
        )
        .addComponent(modelsSection, 1)
        .panel

    private val dynamicTextFields = mutableMapOf<String, JBTextField>()

    // Bumped on every profile selection. An async credential-presence read captures the value at
    // dispatch time and only writes the status label back if it is still current on the EDT, so a
    // slow read for a previously-selected profile cannot clobber the label after the user switches.
    private var credentialCheckGeneration = 0

    init {
        importButton.addActionListener { doImport() }
        exportTemplateButton.addActionListener { doExportTemplate() }
        exportConfiguredButton.addActionListener { doExportConfigured() }
        removeButton.addActionListener { doRemove() }
        connectButton.addActionListener { doConnect() }
        setKeyManuallyButton.addActionListener { doSetKeyManually() }
        refreshModelsButton.addActionListener { doRefreshModels() }

        profileCombo.addActionListener {
            onProfileSelected()
        }

        rebuildProfileList()
    }

    // Left-aligned so the combo sits next to the "Profile:" label. A default (centering) FlowLayout
    // would center the row in the stretched form column, leaving a large gap after the label. Keep the
    // default hgap for normal spacing between the combo and the buttons; only the leading gap matters.
    private fun profileRow(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
        add(profileCombo)
        add(importButton)
        add(exportTemplateButton)
        add(exportConfiguredButton)
        add(removeButton)
    }

    private fun credentialButtonsRow(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        add(connectButton)
        add(setKeyManuallyButton)
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
            // Invalidate any in-flight async credential read so it cannot overwrite this label.
            credentialCheckGeneration++
            credentialStatusLabel.text = "No profile selected"
            connectButton.isEnabled = false
            setKeyManuallyButton.isEnabled = false
            exportTemplateButton.isEnabled = false
            exportConfiguredButton.isEnabled = false
            removeButton.isEnabled = false
            endpointOverrideField.isEnabled = false
            refreshModelsButton.isEnabled = false
            rebuildDynamicFields(emptyList())
            return
        }

        settings.state.activeOpenAiCompatibleProfileId = profile.id

        // Synchronous, PasswordSafe-free UI runs immediately on the EDT so the model catalog/dropdown
        // is always populated regardless of the async credential read below.
        connectButton.isEnabled = true
        setKeyManuallyButton.isEnabled = true
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

        // Read credential presence OFF the EDT (PasswordSafe I/O is prohibited on the EDT). Show a
        // neutral placeholder until it resolves, then update the label only if this is still the
        // selected profile (guard against a stale async result overwriting a newer selection).
        credentialStatusLabel.text = "Checking…"
        val generation = ++credentialCheckGeneration
        val profileId = profile.id
        ApplicationManager.getApplication().executeOnPooledThread {
            val hasCredential = credentialStore.get(profileId).isNotEmpty()
            ApplicationManager.getApplication().invokeLater({
                if (generation == credentialCheckGeneration) {
                    credentialStatusLabel.text = if (hasCredential) "Credentials stored" else "No credentials"
                }
            }, ModalityState.any())
        }
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

        // Step 1: Confirm removal
        val confirmChoice = Messages.showYesNoDialog(
            "Remove profile '${profile.name}'? This will remove the profile configuration and settings.",
            "Remove Profile",
            Messages.getQuestionIcon(),
        )
        if (confirmChoice != Messages.YES) return

        // Step 2: Opt-in to delete credential (default: NO)
        val deleteCredential = Messages.showYesNoDialog(
            "Also delete the stored credential for '${profile.name}'?\n\n" +
                "Select Yes to permanently delete the credential from PasswordSafe.\n" +
                "Select No to keep the credential (you can reconnect later).",
            "Delete Credential?",
            Messages.getQuestionIcon(),
        )

        // Step 3: Opt-in to delete local sessions (default: NO)
        val deleteSessions = Messages.showYesNoDialog(
            "Also delete local session ledgers for '${profile.name}'?\n\n" +
                "Select Yes to permanently delete all chat history for this profile.\n" +
                "Select No to keep the session history.",
            "Delete Sessions?",
            Messages.getQuestionIcon(),
        )

        // Execute removal in order: profile config, then optional credential/sessions.
        profileStore.remove(profile.id)
        rebuildProfileList()

        // PasswordSafe I/O and session-ledger file deletion are prohibited on the EDT — run them off
        // the EDT (fire-and-forget; the profile is already removed from the visible list above).
        val profileId = profile.id
        val clearCredential = deleteCredential == Messages.YES
        val clearSessions = deleteSessions == Messages.YES
        if (clearCredential || clearSessions) {
            ApplicationManager.getApplication().executeOnPooledThread {
                if (clearCredential) credentialStore.clear(profileId)
                if (clearSessions) {
                    com.adobe.clawdea.provider.openai.session.OpenAiSessionScanner.deleteSessionsForProfile(profileId)
                }
            }
        }
    }

    private fun doConnect() {
        val profile = selectedProfile() ?: return
        if (profile.credentialFlow.inputs.isEmpty()) {
            Messages.showInfoMessage("This profile declares no credentials to enter.", "Connect")
            return
        }
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
        if (project == null) {
            Messages.showErrorDialog("No open project found. Open a project to connect.", "Connect")
            return
        }

        // Prompt on the EDT, synchronously, from the action listener so the dialog parents over the
        // still-open Settings dialog. Only the HTTP flow runs off-EDT (below). Cancel returns
        // silently — the button is never disabled and no error is shown.
        val prompt = com.adobe.clawdea.chat.CredentialRenewalDialog(project, profile).promptForCredentials()
            ?: return

        connectButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val resolved = profileStore.resolve(profile.id, System.getenv())
                val liveFieldValues = captureConfiguredValues()
                val configuredValues = OpenAiCompatibleSettingsModel.mergeLiveValues(
                    profile = profile,
                    liveFieldValues = liveFieldValues,
                    resolvedValues = resolved?.configuredValues ?: emptyMap(),
                )
                val executor = com.adobe.clawdea.provider.openai.auth.CredentialFlowExecutor(
                    transport = com.adobe.clawdea.provider.openai.auth.JdkProfileHttpTransport(),
                    credentialStore = credentialStore,
                )
                // execute() runs the declarative HTTP steps, persists the durable credential to
                // PasswordSafe, and clears secret CharArrays in its own finally.
                val result = executor.execute(
                    profile = profile,
                    secretInputs = prompt.secretInputs,
                    textInputs = prompt.textInputs,
                    configuredValues = configuredValues,
                    environment = System.getenv(),
                )
                val stored = result.credential.isNotBlank()
                ApplicationManager.getApplication().invokeLater({
                    if (stored) {
                        credentialStatusLabel.text = "Credentials stored"
                        Messages.showInfoMessage("Credentials renewed successfully.", "Connect")
                    } else {
                        Messages.showErrorDialog("Credential flow completed but returned an empty credential.", "Connect")
                    }
                }, ModalityState.any())
            } catch (e: com.adobe.clawdea.provider.openai.auth.CredentialFlowException) {
                // Surface the real flow error (e.g. "Step login failed: expected [200], got 401" or
                // "Path $.access_token: missing field access_token") so the user can correct the profile.
                ApplicationManager.getApplication().invokeLater({
                    Messages.showErrorDialog("Connection failed: ${e.message}", "Connect")
                }, ModalityState.any())
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    Messages.showErrorDialog("Connection failed: ${e.message ?: e.javaClass.simpleName}", "Connect")
                }, ModalityState.any())
            } finally {
                // Defensive: execute() already clears secrets, but ensure they are cleared even if we
                // failed before reaching execute() (e.g. resolve threw).
                prompt.secretInputs.values.forEach { it.fill(' ') }
                ApplicationManager.getApplication().invokeLater({
                    connectButton.isEnabled = true
                }, ModalityState.any())
            }
        }
    }

    /**
     * Manual credential override: paste an API key directly into PasswordSafe, bypassing the
     * profile's sign-in flow. For when Connect fails server-side (e.g. a 500) and the user already
     * has a valid key. Prompts on the EDT (modal, parents over Settings); the key is read as a
     * CharArray, written to the same PasswordSafe slot the flow uses, and zeroed after. The write
     * runs off the EDT (PasswordSafe I/O is prohibited on the EDT).
     */
    private fun doSetKeyManually() {
        val profile = selectedProfile() ?: return
        val dialog = ManualApiKeyDialog(profile.name)
        val key = dialog.promptForKey() ?: return // null = cancelled or blank
        val profileId = profile.id
        setKeyManuallyButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                credentialStore.set(profileId, String(key))
            } finally {
                key.fill(' ')
            }
            ApplicationManager.getApplication().invokeLater({
                setKeyManuallyButton.isEnabled = true
                if (profileId == selectedProfile()?.id) {
                    credentialStatusLabel.text = "Credentials stored"
                }
                Messages.showInfoMessage("API key saved for '${profile.name}'.", "Set API Key Manually")
            }, ModalityState.any())
        }
    }

    private fun doRefreshModels() {
        val profile = selectedProfile() ?: return

        val resolved = profileStore.resolve(profile.id, System.getenv())
        if (resolved == null) {
            Messages.showErrorDialog("Could not resolve the profile configuration.", "Refresh Models")
            return
        }

        val liveFieldValues = captureConfiguredValues()
        val liveConfiguredValues = OpenAiCompatibleSettingsModel.mergeLiveValues(
            profile = profile,
            liveFieldValues = liveFieldValues,
            resolvedValues = resolved.configuredValues,
        )
        val resolvedWithLiveValues = resolved.copy(configuredValues = liveConfiguredValues)

        refreshModelsButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            // Read the credential OFF the EDT (PasswordSafe I/O is prohibited on the EDT).
            val credential = credentialStore.get(profile.id)
            if (credential.isBlank()) {
                ApplicationManager.getApplication().invokeLater({
                    refreshModelsButton.isEnabled = true
                    Messages.showErrorDialog("No credential stored for this profile. Connect it first.", "Refresh Models")
                }, ModalityState.any())
                return@executeOnPooledThread
            }
            val client = com.adobe.clawdea.provider.openai.client.OpenAiCompatibleClient()
            val freshModels = client.listModels(resolvedWithLiveValues, credential)
            if (freshModels == null) {
                ApplicationManager.getApplication().invokeLater({
                    refreshModelsButton.text = "Refresh Models"
                    refreshModelsButton.isEnabled = true
                    Messages.showErrorDialog("Failed to fetch models from the provider. Check the endpoint and credential.", "Refresh Models")
                }, ModalityState.any())
                return@executeOnPooledThread
            }

            // Verify each fetched model's tool-calling capability CONCURRENTLY (all probes in parallel,
            // off the EDT). A probe that throws or reports UNKNOWN leaves capability="unknown". The
            // freshly-verified capability rides on each fresh row into the merge below, so provider
            // (non-userAdded) rows relearn their capability on every refresh.
            ApplicationManager.getApplication().invokeLater({
                refreshModelsButton.text = "Verifying…"
            }, ModalityState.any())
            val futures = freshModels.map { model ->
                ApplicationManager.getApplication().executeOnPooledThread<com.adobe.clawdea.gateway.ModelEntry> {
                    val capability = try {
                        com.adobe.clawdea.provider.openai.catalog.ModelCapabilityVerifier.verify(
                            resolvedWithLiveValues, credential, model.id,
                        )
                    } catch (e: Exception) {
                        com.adobe.clawdea.provider.openai.catalog.ModelCapability.UNKNOWN
                    }
                    model.copy(
                        capability = com.adobe.clawdea.provider.openai.catalog.ModelCapabilityResolver.capabilityToString(capability),
                    )
                }
            }
            val verifiedModels = futures.map { it.get() }

            ApplicationManager.getApplication().invokeLater({
                refreshModelsButton.text = "Refresh Models"
                refreshModelsButton.isEnabled = true

                val existingCatalog = modelTableModel.rows.toList()
                val merged = ModelCatalogMerge.merge(existingCatalog, verifiedModels)
                modelTableModel.replaceAll(merged)

                val catalogKey = ProviderRegistry.catalogKey(ProviderRegistry.OPENAI_COMPATIBLE_ID, profile.id)
                settings.state.modelCatalogs[catalogKey] = merged.toMutableList()

                Messages.showInfoMessage("Fetched and verified ${verifiedModels.size} models from the provider.", "Refresh Models")
            }, ModalityState.any())
        }
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
