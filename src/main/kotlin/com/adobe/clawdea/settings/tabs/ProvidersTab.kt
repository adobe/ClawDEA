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
// src/main/kotlin/com/adobe/clawdea/settings/tabs/ProvidersTab.kt
package com.adobe.clawdea.settings.tabs

import com.adobe.clawdea.CLAUDE_DIR
import com.adobe.clawdea.auth.*
import com.adobe.clawdea.cli.CliProcess
import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.settings.ClawDEASettings
import com.adobe.clawdea.settings.OpenAiCompatibleSettingsCard
import com.adobe.clawdea.settings.OpenAiSubscriptionCardPanel
import com.adobe.clawdea.settings.SubscriptionCardPanel
import com.adobe.clawdea.settings.ToolApprovalModeUi
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * Providers section extracted from the flat settings panel: API-provider combo,
 * provider-specific credential cards, Check Connection, CLI paths, completions,
 * tool approval, auto-accept edits, and the per-provider Models catalog table.
 *
 * The Models catalog dirty-check is folded into [isModifiedFrom] via
 * [isModelsModified], and [applyTo] persists the catalog into
 * [ClawDEASettings.State.modelCatalogs] so the whole triple lives in this tab.
 */
class ProvidersTab : SettingsTab {

    override val title: String = "Providers"

    // Provider selection
    private val PROVIDERS = arrayOf(
        "Anthropic (direct)",
        "Amazon Bedrock",
        "Google Vertex AI",
        "Claude subscription (Pro / Max / Team / Enterprise)",
        "OpenAI (direct)",
        "OpenAI (ChatGPT subscription)",
        "OpenAI-compatible",
    )
    private val PROVIDER_KEYS = arrayOf("anthropic", "bedrock", "vertex", "subscription", "openai", "openai-subscription", "openai-compatible")
    val apiProviderCombo = ComboBox(DefaultComboBoxModel(PROVIDERS))

    // Anthropic fields
    val apiKeyField = JBPasswordField()
    private val apiKeyHint = JBLabel("Leave blank to use ANTHROPIC_API_KEY from your environment.").apply {
        foreground = java.awt.Color(166, 173, 200) // muted
        font = font.deriveFont(11f)
    }
    private val apiKeyWarning = JBLabel("").apply {
        foreground = java.awt.Color(249, 226, 175) // yellow
        font = font.deriveFont(11f)
    }
    private val subscriptionDetectedHint = JBLabel("").apply {
        foreground = java.awt.Color(166, 173, 200) // muted
        font = font.deriveFont(11f)
    }

    // Bedrock fields
    val bedrockRegionField = JBTextField("", 20)
    val bedrockBearerTokenField = JBPasswordField()
    private val bedrockHint = JBLabel("Uses AWS credentials from your environment (env vars, ~/.aws/credentials, or SSO).").apply {
        foreground = java.awt.Color(166, 173, 200)
        font = font.deriveFont(11f)
    }

    // Vertex fields
    val vertexRegionField = JBTextField("", 20)
    val vertexProjectIdField = JBTextField("", 20)
    private val vertexHint = JBLabel("Uses Google Cloud credentials from your environment (gcloud auth, service account).").apply {
        foreground = java.awt.Color(166, 173, 200)
        font = font.deriveFont(11f)
    }

    // OpenAI fields
    val openAiApiKeyField = JBPasswordField()
    private val openAiHint = JBLabel("Leave blank to use OPENAI_API_KEY from your environment.").apply {
        foreground = java.awt.Color(166, 173, 200) // muted
        font = font.deriveFont(11f)
    }

    // Subscription card
    private val subscriptionCard = SubscriptionCardPanel()

    // OpenAI (ChatGPT subscription) card
    private val openAiSubscriptionCard = OpenAiSubscriptionCardPanel()

    // OpenAI-compatible card
    private val openAiCompatibleCard = OpenAiCompatibleSettingsCard()

    // Provider-specific sub-panels inside a CardLayout
    private val providerCards = JPanel(CardLayout()).apply {
        add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent(JBLabel("API Key (fallback):"), apiKeyField, 1, false)
                .addComponent(apiKeyHint, 2)
                .addComponent(apiKeyWarning, 2)
                .addComponent(subscriptionDetectedHint, 2)
                .panel,
            "anthropic"
        )
        add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent(JBLabel("AWS Region:"), bedrockRegionField, 1, false)
                .addLabeledComponent(JBLabel("Bearer Token:"), bedrockBearerTokenField, 1, false)
                .addComponent(bedrockHint, 2)
                .panel,
            "bedrock"
        )
        add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent(JBLabel("GCP Region:"), vertexRegionField, 1, false)
                .addLabeledComponent(JBLabel("GCP Project ID:"), vertexProjectIdField, 1, false)
                .addComponent(vertexHint, 2)
                .panel,
            "vertex"
        )
        add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent(JBLabel("OpenAI API Key:"), openAiApiKeyField, 1, false)
                .addComponent(openAiHint, 2)
                .panel,
            "openai"
        )
        add(subscriptionCard.panel, "subscription")
        add(openAiSubscriptionCard.panel, "openai-subscription")
        add(openAiCompatibleCard.panel, "openai-compatible")
    }

    // Check Connection
    private val checkConnectionButton = JButton("Check Connection")
    private val connectionResultLabel = JBLabel("").apply {
        font = font.deriveFont(11f)
    }
    private val connectionRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        add(checkConnectionButton)
        add(connectionResultLabel)
    }

    // Common fields
    val cliPathField = JBTextField("claude", 30)
    val codexCliPathField = JBTextField("codex", 30)
    private val codexCliPathHint = JBLabel("Path to the OpenAI codex CLI (used by the OpenAI ChatGPT subscription provider).").apply {
        foreground = java.awt.Color(166, 173, 200)
        font = font.deriveFont(11f)
    }
    val completionsEnabledCheckbox = JBCheckBox("Enable inline completions", true)
    private val COMPLETION_MODELS = arrayOf("Sonnet", "Haiku")
    private val COMPLETION_MODEL_KEYS = arrayOf("sonnet", "haiku")
    val completionsModelCombo = ComboBox(DefaultComboBoxModel(COMPLETION_MODELS))
    val completionsDebounceField = JBTextField("300", 6)
    val completionsManualOnlyCheckbox = JBCheckBox("Only request completions on hotkey (Trigger Inline Completion, default Alt+\\)", false).apply {
        toolTipText = "When on, completions never fire automatically as you type — they are requested only when you invoke the \"Trigger Inline Completion\" action. Rebind the hotkey in Settings → Keymap."
    }
    val toolApprovalCombo = ComboBox(ToolApprovalModeUi.comboBoxModel()).apply {
        toolTipText = ToolApprovalModeUi.TOOLTIP_TEXT
        ToolApprovalModeUi.installRenderer(this)
    }
    val autoAcceptEditsCheckbox = JBCheckBox("Auto-accept file edits (still reversible from the chat diff link)", false)

    private val cliPathWarning = JBLabel("").apply {
        foreground = java.awt.Color(243, 139, 168) // red
        font = font.deriveFont(11f)
    }

    // Models catalog (per-provider)
    private val transientCatalogs: MutableMap<String, MutableList<ModelEntry>> = mutableMapOf()
    private var currentCatalogProvider: String = "anthropic"
    private val modelsSectionLabel: JBLabel = JBLabel("Models")
    private val modelTableModel = ModelCatalogTableModel()
    private val modelsSection: JPanel = run {
        val table = JBTable(modelTableModel)
        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { modelTableModel.addRow() }
            .setRemoveAction {
                val row = table.selectedRow
                if (row >= 0) modelTableModel.removeRow(table.convertRowIndexToModel(row))
            }
        decorator.createPanel().apply {
            preferredSize = Dimension(preferredSize.width, 160)
        }
    }

    override val component: JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("API Provider:"), apiProviderCombo, 1, false)
        .addComponent(providerCards, 1)
        .addComponent(connectionRow, 1)
        .addLabeledComponent(JBLabel("Claude CLI path:"), cliPathField, 1, false)
        .addComponent(cliPathWarning, 2)
        .addLabeledComponent(JBLabel("Codex CLI path:"), codexCliPathField, 1, false)
        .addComponent(codexCliPathHint, 2)
        .addComponent(completionsEnabledCheckbox, 1)
        .addLabeledComponent(JBLabel("Completions model:"), completionsModelCombo, 1, false)
        .addLabeledComponent(JBLabel("Completions debounce (ms):"), completionsDebounceField, 1, false)
        .addComponent(completionsManualOnlyCheckbox, 1)
        .addLabeledComponent(JBLabel("Tool approval:"), toolApprovalCombo, 1, false)
        .addComponent(autoAcceptEditsCheckbox, 1)
        .addSeparator()
        .addLabeledComponent(modelsSectionLabel, JPanel(), 0, false)
        .addComponent(modelsSection, 1)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    init {
        cliPathField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                validateCliPath()
            }
        })
        apiKeyField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                validateApiKey()
            }
        })
        apiProviderCombo.addActionListener {
            showProviderCard()
            val newProvider = providerKey()
            if (newProvider != currentCatalogProvider) {
                flushCurrentTableToTransient()
                currentCatalogProvider = newProvider
                modelTableModel.replaceAll(transientCatalogs[newProvider] ?: mutableListOf())
                modelsSectionLabel.text = "Models (${newProvider})"
            }
            updateApiKeyLabel()
            connectionResultLabel.text = ""
        }
        checkConnectionButton.addActionListener { doCheckConnection() }
        showProviderCard()
        updateApiKeyLabel()
    }

    fun getPreferredFocusedComponent(): JComponent = apiProviderCombo

    fun dispose() {
        com.intellij.openapi.util.Disposer.dispose(subscriptionCard)
        com.intellij.openapi.util.Disposer.dispose(openAiSubscriptionCard)
        com.intellij.openapi.util.Disposer.dispose(openAiCompatibleCard)
    }

    private fun doCheckConnection() {
        checkConnectionButton.isEnabled = false
        connectionResultLabel.foreground = java.awt.Color(166, 173, 200)
        connectionResultLabel.text = "Testing…"

        val provider = buildProviderFromForm()

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = provider.testConnection()
            ApplicationManager.getApplication().invokeLater({
                checkConnectionButton.isEnabled = true
                connectionResultLabel.foreground = if (result.success) {
                    java.awt.Color(166, 227, 161) // green
                } else {
                    java.awt.Color(243, 139, 168) // red
                }
                connectionResultLabel.text = result.message
            }, ModalityState.any())
        }
    }

    private fun buildProviderFromForm(): AuthProvider {
        val key = selectedProviderKey()
        return when (key) {
            "anthropic" -> AnthropicAuthProvider(
                String(apiKeyField.password),
                System.getenv("ANTHROPIC_API_KEY"),
            )
            "bedrock" -> BedrockAuthProvider(
                bedrockRegionField.text,
                String(bedrockBearerTokenField.password),
            )
            "vertex" -> VertexAuthProvider(
                vertexRegionField.text,
                vertexProjectIdField.text,
            )
            "subscription" -> SubscriptionAuthProvider(
                SubscriptionAuth.getInstance().getStatus().isSignedIn(),
            )
            "openai" -> OpenAIAuthProvider(
                String(openAiApiKeyField.password),
                System.getenv("OPENAI_API_KEY"),
            )
            "openai-subscription" -> OpenAiSubscriptionAuthProvider(
                CodexSubscriptionAuth.getInstance().getStatus().isSignedIn(),
            )
            "openai-compatible" -> com.adobe.clawdea.provider.openai.auth.OpenAiCompatibleAuthProvider()
            else -> AnthropicAuthProvider(
                String(apiKeyField.password),
                System.getenv("ANTHROPIC_API_KEY"),
            )
        }
    }

    private fun updateApiKeyLabel() {
        val completionsOnly = selectedProviderKey() == "subscription"
        apiKeyHint.text = if (completionsOnly) {
            "Optional — add an Anthropic API key to enable inline completions while using subscription auth."
        } else {
            "Leave blank to use ANTHROPIC_API_KEY from your environment."
        }
    }

    private fun showProviderCard() {
        val layout = providerCards.layout as CardLayout
        layout.show(providerCards, selectedProviderKey())
        refreshSubscriptionDetectionHint()

        // Hide Claude/Codex CLI path fields when OpenAI-compatible provider is selected
        val hideCliPaths = selectedProviderKey() == "openai-compatible"
        cliPathField.isVisible = !hideCliPaths
        cliPathWarning.isVisible = !hideCliPaths
        codexCliPathField.isVisible = !hideCliPaths
        codexCliPathHint.isVisible = !hideCliPaths
    }

    private fun refreshSubscriptionDetectionHint() {
        val onAnthropic = selectedProviderKey() == "anthropic"
        val keyBlank = String(apiKeyField.password).isBlank()
        val envKeyBlank = System.getenv("ANTHROPIC_API_KEY").isNullOrBlank()
        val credsExist = java.io.File(
            System.getProperty("user.home"),
            "$CLAUDE_DIR/.credentials.json"
        ).let { it.isFile && it.length() > 0 }

        subscriptionDetectedHint.text = if (onAnthropic && keyBlank && envKeyBlank && credsExist) {
            "You appear to be signed in to Claude Code — switch to the Claude subscription provider to use it."
        } else {
            ""
        }
    }

    private fun validateCliPath() {
        val path = cliPathField.text.trim()
        val resolved = CliProcess("/tmp").resolveCliPath(path)
        val file = java.io.File(resolved)
        if (resolved == "claude" || !file.isFile || !file.canExecute()) {
            cliPathWarning.text = "CLI not found at this path."
        } else {
            cliPathWarning.text = ""
        }
    }

    private fun validateApiKey() {
        val key = String(apiKeyField.password).trim()
        val envKey = System.getenv("ANTHROPIC_API_KEY")
        if (key.isBlank() && envKey.isNullOrBlank()) {
            apiKeyWarning.text = "No API key detected. Set one here or export ANTHROPIC_API_KEY in your shell."
        } else {
            apiKeyWarning.text = ""
        }
        refreshSubscriptionDetectionHint()
    }

    private fun selectedProviderKey(): String {
        val idx = apiProviderCombo.selectedIndex
        return if (idx >= 0) PROVIDER_KEYS[idx] else "anthropic"
    }

    /**
     * Reads the API key from the card the user last interacted with.
     * Both the Anthropic card and the Subscription card expose the same
     * `state.apiKey`; we take the active card's value when the user is on
     * that provider, otherwise fall back to the Anthropic card.
     */
    private fun effectiveApiKey(): String =
        if (selectedProviderKey() == "subscription")
            String(subscriptionCard.apiKeyField.password)
        else
            String(apiKeyField.password)

    private fun selectProviderByKey(key: String) {
        val idx = PROVIDER_KEYS.indexOf(key)
        apiProviderCombo.selectedIndex = if (idx >= 0) idx else 0
    }

    override fun loadFrom(state: ClawDEASettings.State) {
        val settings = ClawDEASettings.getInstance()
        selectProviderByKey(state.apiProvider)
        apiKeyField.text = settings.getApiKey()
        subscriptionCard.apiKeyField.text = settings.getApiKey()
        openAiApiKeyField.text = settings.getOpenAIApiKey()
        cliPathField.text = state.cliPath
        codexCliPathField.text = state.codexCliPath
        completionsEnabledCheckbox.isSelected = state.completionsEnabled
        selectCompletionsModel(state.completionsModel)
        completionsDebounceField.text = state.completionsDebounceMs.toString()
        completionsManualOnlyCheckbox.isSelected = state.completionsManualOnly
        toolApprovalCombo.selectedIndex = ToolApprovalModeUi.indexForKey(state.toolApprovalMode)
        autoAcceptEditsCheckbox.isSelected = state.autoAcceptEdits
        bedrockRegionField.text = state.bedrockRegion
        bedrockBearerTokenField.text = settings.getBedrockBearerToken()
        vertexRegionField.text = state.vertexRegion
        vertexProjectIdField.text = state.vertexProjectId
        showProviderCard()
        updateApiKeyLabel()
        openAiCompatibleCard.load(state)
        loadModels(state.modelCatalogs)
    }

    override fun applyTo(state: ClawDEASettings.State) {
        val settings = ClawDEASettings.getInstance()
        state.apiProvider = selectedProviderKey()
        settings.setApiKey(effectiveApiKey())
        settings.setOpenAIApiKey(String(openAiApiKeyField.password))
        state.cliPath = cliPathField.text
        state.codexCliPath = codexCliPathField.text
        state.completionsEnabled = completionsEnabledCheckbox.isSelected
        state.completionsModel = selectedCompletionsModelKey()
        state.completionsDebounceMs = completionsDebounceField.text.toIntOrNull() ?: 300
        state.completionsManualOnly = completionsManualOnlyCheckbox.isSelected
        state.toolApprovalMode = ToolApprovalModeUi.keyForIndex(toolApprovalCombo.selectedIndex)
        state.autoAcceptEdits = autoAcceptEditsCheckbox.isSelected
        state.bedrockRegion = bedrockRegionField.text
        settings.setBedrockBearerToken(String(bedrockBearerTokenField.password))
        state.vertexRegion = vertexRegionField.text
        state.vertexProjectId = vertexProjectIdField.text
        openAiCompatibleCard.apply(state)
        state.modelCatalogs = saveModels()
    }

    override fun isModifiedFrom(state: ClawDEASettings.State): Boolean {
        val settings = ClawDEASettings.getInstance()
        return selectedProviderKey() != state.apiProvider ||
            effectiveApiKey() != settings.getApiKey() ||
            String(openAiApiKeyField.password) != settings.getOpenAIApiKey() ||
            cliPathField.text != state.cliPath ||
            codexCliPathField.text != state.codexCliPath ||
            completionsEnabledCheckbox.isSelected != state.completionsEnabled ||
            selectedCompletionsModelKey() != state.completionsModel ||
            completionsDebounceField.text != state.completionsDebounceMs.toString() ||
            completionsManualOnlyCheckbox.isSelected != state.completionsManualOnly ||
            ToolApprovalModeUi.keyForIndex(toolApprovalCombo.selectedIndex) != state.toolApprovalMode ||
            autoAcceptEditsCheckbox.isSelected != state.autoAcceptEdits ||
            bedrockRegionField.text != state.bedrockRegion ||
            String(bedrockBearerTokenField.password) != settings.getBedrockBearerToken() ||
            vertexRegionField.text != state.vertexRegion ||
            vertexProjectIdField.text != state.vertexProjectId ||
            openAiCompatibleCard.isModified(state) ||
            isModelsModified(state.modelCatalogs)
    }

    // ------------------------------------------------------------------
    // Models catalog load/save/dirty-check (per-provider)
    // ------------------------------------------------------------------

    private fun providerKey(): String =
        PROVIDER_KEYS[apiProviderCombo.selectedIndex.coerceIn(0, PROVIDER_KEYS.lastIndex)]

    fun loadModels(catalogs: Map<String, List<ModelEntry>>) {
        transientCatalogs.clear()
        for ((k, v) in catalogs) {
            transientCatalogs[k] = v.map { it.copy() }.toMutableList()
        }
        currentCatalogProvider = providerKey()
        modelTableModel.replaceAll(transientCatalogs[currentCatalogProvider] ?: mutableListOf())
        modelsSectionLabel.text = "Models (${currentCatalogProvider})"
    }

    fun isModelsModified(catalogs: Map<String, List<ModelEntry>>): Boolean {
        flushCurrentTableToTransient()

        // For openai-compatible, check the active profile's catalog
        if (selectedProviderKey() == "openai-compatible") {
            val activeId = ClawDEASettings.getInstance().state.activeOpenAiCompatibleProfileId
            if (activeId.isNotBlank()) {
                val catalogKey = com.adobe.clawdea.provider.ProviderRegistry.catalogKey(
                    com.adobe.clawdea.provider.ProviderRegistry.OPENAI_COMPATIBLE_ID,
                    activeId
                )
                val savedCatalog = catalogs[catalogKey] ?: emptyList()
                if (openAiCompatibleCard.isModelsModified(savedCatalog)) return true
            }
        }

        if (transientCatalogs.size != catalogs.size) return true
        for ((k, persisted) in catalogs) {
            val transient = transientCatalogs[k] ?: return true
            if (persisted.size != transient.size) return true
            val mismatch = persisted.zip(transient).any { (a, b) ->
                a.id != b.id || a.displayName != b.displayName || a.userAdded != b.userAdded
            }
            if (mismatch) return true
        }
        return false
    }

    fun saveModels(): MutableMap<String, MutableList<ModelEntry>> {
        flushCurrentTableToTransient()
        val out: MutableMap<String, MutableList<ModelEntry>> = mutableMapOf()
        for ((k, v) in transientCatalogs) {
            out[k] = v.map { it.copy() }.toMutableList()
        }

        // For openai-compatible, save the active profile's catalog
        if (selectedProviderKey() == "openai-compatible") {
            val activeId = ClawDEASettings.getInstance().state.activeOpenAiCompatibleProfileId
            if (activeId.isNotBlank()) {
                val catalogKey = com.adobe.clawdea.provider.ProviderRegistry.catalogKey(
                    com.adobe.clawdea.provider.ProviderRegistry.OPENAI_COMPATIBLE_ID,
                    activeId
                )
                out[catalogKey] = openAiCompatibleCard.saveModels()
            }
        }

        return out
    }

    private fun selectedCompletionsModelKey(): String {
        val idx = completionsModelCombo.selectedIndex
        return if (idx >= 0) COMPLETION_MODEL_KEYS[idx] else "sonnet"
    }

    private fun selectCompletionsModel(key: String) {
        val idx = COMPLETION_MODEL_KEYS.indexOf(key)
        completionsModelCombo.selectedIndex = if (idx >= 0) idx else 0
    }

    private fun flushCurrentTableToTransient() {
        transientCatalogs[currentCatalogProvider] = modelTableModel.rows.map { it.copy() }.toMutableList()
    }

    private class ModelCatalogTableModel(
        val rows: MutableList<ModelEntry> = mutableListOf(),
    ) : AbstractTableModel() {
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = when (column) {
            0 -> "ID"
            1 -> "Display name"
            else -> ""
        }
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
            0 -> rows[rowIndex].id
            1 -> rows[rowIndex].displayName
            else -> ""
        }
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true
        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val str = aValue?.toString() ?: ""
            val entry = rows[rowIndex]
            when (columnIndex) {
                0 -> entry.id = str
                1 -> entry.displayName = str
            }
            entry.userAdded = true
            fireTableRowsUpdated(rowIndex, rowIndex)
        }

        fun addRow() {
            rows.add(ModelEntry(id = "", displayName = "", userAdded = true))
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }

        fun removeRow(index: Int) {
            rows.removeAt(index)
            fireTableRowsDeleted(index, index)
        }

        fun replaceAll(newRows: List<ModelEntry>) {
            rows.clear()
            rows.addAll(newRows.map { it.copy() })
            fireTableDataChanged()
        }
    }
}
