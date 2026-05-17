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
// src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettingsPanel.kt
package com.adobe.clawdea.settings

import com.adobe.clawdea.CLAUDE_DIR
import com.adobe.clawdea.auth.*
import com.adobe.clawdea.cli.CliProcess
import com.adobe.clawdea.gateway.ModelEntry
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

class ClawDEASettingsPanel {
    // Provider selection
    private val PROVIDERS = arrayOf(
        "Anthropic (direct)",
        "Amazon Bedrock",
        "Google Vertex AI",
        "Claude subscription (Pro / Max / Team / Enterprise)",
    )
    private val PROVIDER_KEYS = arrayOf("anthropic", "bedrock", "vertex", "subscription")
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

    // Subscription card
    private val subscriptionCard = SubscriptionCardPanel()

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
        add(subscriptionCard.panel, "subscription")
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
    val completionsEnabledCheckbox = JBCheckBox("Enable inline completions", true)
    private val COMPLETION_MODELS = arrayOf("Sonnet", "Haiku")
    private val COMPLETION_MODEL_KEYS = arrayOf("sonnet", "haiku")
    val completionsModelCombo = ComboBox(DefaultComboBoxModel(COMPLETION_MODELS))
    val completionsDebounceField = JBTextField("300", 6)
    val toolApprovalCombo = ComboBox(ToolApprovalModeUi.comboBoxModel()).apply {
        toolTipText = ToolApprovalModeUi.TOOLTIP_TEXT
        ToolApprovalModeUi.installRenderer(this)
    }
    val autoAcceptEditsCheckbox = JBCheckBox("Auto-accept file edits (still reversible from the chat diff link)", false)
    val completionTokenBudgetField = JBTextField("2048", 6)
    val chatTokenBudgetField = JBTextField("16384", 6)
    val actionTokenBudgetField = JBTextField("4096", 6)
    val cliExtraArgsField = JBTextField("", 30)
    val cliEnvScriptField = JBTextField("", 30)
    val enablePsiCollectorCheckbox = JBCheckBox("Enable PSI semantic context", true)
    val enableGitCollectorCheckbox = JBCheckBox("Enable Git context", true)
    val preloadSkillCatalogCheckbox = JBCheckBox("Preload skill catalog into system prompt", true)
    val gatewayBareModeCheckbox = JBCheckBox(
        "Use minimal-mode CLI for completions (--bare; requires API-key auth)",
        true,
    )
    val enableKnowledgeLayerCheckbox = JBCheckBox("Enable knowledge layer", true).apply {
        toolTipText = "Main switch. When off, ClawDEA stops assembling MAP/wiki/notes/workspace into the primer and disables the related MCP tools."
    }
    val enableWikiLibrarianCheckbox = JBCheckBox("Enable wiki librarian", true).apply {
        toolTipText = "When on, the primer instructs the main agent to delegate design questions to the wiki-librarian Claude Code subagent via Task. The subagent is injected per-session via --agents and search_wiki is not registered as an MCP tool. When off, the legacy search_wiki probe directive is used."
    }
    val enableWorkspaceCheckbox = JBCheckBox("Enable workspace manifest", true).apply {
        toolTipText = "Read sibling repos from .clawdea-workspace.md and surface them via list_workspace_repos / read_sibling_* MCP tools."
    }
    val autoUpdateWikiCheckbox = JBCheckBox("Auto-update wiki on drift", false).apply {
        toolTipText = "When on, high-confidence drift fixes (single-match code renames; manifest comment-out) apply silently; learn-on-probe-miss writes use Write/Edit instead of propose_*. When off, every change goes through diff review."
    }
    val enableDreamWikiMaintenanceCheckbox = JBCheckBox("Enable Dream wiki maintenance", true)
    val dreamWikiMinElapsedHoursField = JBTextField(DreamWikiSettingsParser.MIN_ELAPSED_HOURS_DEFAULT.toString(), 6)
    val dreamWikiMinSignalUnitsField = JBTextField(DreamWikiSettingsParser.MIN_SIGNAL_UNITS_DEFAULT.toString(), 6)
    val dreamWikiScanThrottleMinutesField = JBTextField(DreamWikiSettingsParser.SCAN_THROTTLE_MINUTES_DEFAULT.toString(), 6)

    // Profiling fields
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

    val panel: JPanel = FormBuilder.createFormBuilder()
        .addSeparator()
        .addLabeledComponent(JBLabel("General"), JPanel(), 0, false)
        .addLabeledComponent(JBLabel("API Provider:"), apiProviderCombo, 1, false)
        .addComponent(providerCards, 1)
        .addComponent(connectionRow, 1)
        .addLabeledComponent(JBLabel("Claude CLI path:"), cliPathField, 1, false)
        .addComponent(cliPathWarning, 2)
        .addComponent(completionsEnabledCheckbox, 1)
        .addLabeledComponent(JBLabel("Completions model:"), completionsModelCombo, 1, false)
        .addLabeledComponent(JBLabel("Completions debounce (ms):"), completionsDebounceField, 1, false)
        .addLabeledComponent(JBLabel("Tool approval:"), toolApprovalCombo, 1, false)
        .addComponent(autoAcceptEditsCheckbox, 1)
        .addSeparator()
        .addLabeledComponent(JBLabel("Knowledge layer"), JPanel(), 0, false)
        .addComponent(enableKnowledgeLayerCheckbox, 1)
        .addComponent(enableWikiLibrarianCheckbox, 2)
        .addComponent(enableWorkspaceCheckbox, 2)
        .addComponent(autoUpdateWikiCheckbox, 2)
        .addComponent(enableDreamWikiMaintenanceCheckbox, 2)
        .addLabeledComponent(JBLabel("Dream min elapsed (hours):"), dreamWikiMinElapsedHoursField, 2, false)
        .addLabeledComponent(JBLabel("Dream min signal units:"), dreamWikiMinSignalUnitsField, 2, false)
        .addLabeledComponent(JBLabel("Dream scan throttle (minutes):"), dreamWikiScanThrottleMinutesField, 2, false)
        .addSeparator()
        .addLabeledComponent(JBLabel("Profiling"), JPanel(), 0, false)
        .addLabeledComponent(JBLabel("Backend:"), profilingBackendCombo, 1, false)
        .addLabeledComponent(JBLabel("Sampling interval (ms):"), profilingSamplingIntervalField, 1, false)
        .addLabeledComponent(JBLabel("Max duration (seconds):"), profilingMaxDurationField, 1, false)
        .addLabeledComponent(JBLabel("Max recording size (MB):"), profilingMaxRecordingMbField, 1, false)
        .addLabeledComponent(JBLabel("Stack depth:"), profilingStackDepthField, 1, false)
        .addLabeledComponent(JBLabel("Max stored recordings:"), profilingMaxRecordingsField, 1, false)
        .addLabeledComponent(JBLabel("Max storage (GB):"), profilingMaxStorageGbField, 1, false)
        .addComponent(profilingAutoAnalyzeCheckbox, 1)
        .addLabeledComponent(JBLabel("Top-N hotspots:"), profilingTopNField, 1, false)
        .addSeparator()
        .addLabeledComponent(modelsSectionLabel, JPanel(), 0, false)
        .addComponent(modelsSection, 1)
        .addSeparator()
        .addLabeledComponent(JBLabel("Advanced"), JPanel(), 0, false)
        .addLabeledComponent(JBLabel("Completion token budget:"), completionTokenBudgetField, 1, false)
        .addLabeledComponent(JBLabel("Chat token budget:"), chatTokenBudgetField, 1, false)
        .addLabeledComponent(JBLabel("Action token budget:"), actionTokenBudgetField, 1, false)
        .addLabeledComponent(JBLabel("CLI extra args:"), cliExtraArgsField, 1, false)
        .addLabeledComponent(JBLabel("CLI env script:"), cliEnvScriptField, 1, false)
        .addComponent(enablePsiCollectorCheckbox, 1)
        .addComponent(enableGitCollectorCheckbox, 1)
        .addComponent(preloadSkillCatalogCheckbox, 1)
        .addComponent(gatewayBareModeCheckbox, 1)
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
        enableKnowledgeLayerCheckbox.addItemListener {
            updateKnowledgeLayerEnabledState()
        }
        enableDreamWikiMaintenanceCheckbox.addItemListener {
            updateKnowledgeLayerEnabledState()
        }
        showProviderCard()
        updateApiKeyLabel()
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

    fun loadFrom(state: ClawDEASettings.State) {
        val settings = ClawDEASettings.getInstance()
        selectProviderByKey(state.apiProvider)
        apiKeyField.text = settings.getApiKey()
        subscriptionCard.apiKeyField.text = settings.getApiKey()
        cliPathField.text = state.cliPath
        completionsEnabledCheckbox.isSelected = state.completionsEnabled
        selectCompletionsModel(state.completionsModel)
        completionsDebounceField.text = state.completionsDebounceMs.toString()
        toolApprovalCombo.selectedIndex = ToolApprovalModeUi.indexForKey(state.toolApprovalMode)
        autoAcceptEditsCheckbox.isSelected = state.autoAcceptEdits
        bedrockRegionField.text = state.bedrockRegion
        bedrockBearerTokenField.text = settings.getBedrockBearerToken()
        vertexRegionField.text = state.vertexRegion
        vertexProjectIdField.text = state.vertexProjectId
        completionTokenBudgetField.text = state.completionTokenBudget.toString()
        chatTokenBudgetField.text = state.chatTokenBudget.toString()
        actionTokenBudgetField.text = state.actionTokenBudget.toString()
        cliExtraArgsField.text = state.cliExtraArgs
        cliEnvScriptField.text = state.cliEnvScript
        enablePsiCollectorCheckbox.isSelected = state.enablePsiCollector
        enableGitCollectorCheckbox.isSelected = state.enableGitCollector
        preloadSkillCatalogCheckbox.isSelected = state.preloadSkillCatalog
        gatewayBareModeCheckbox.isSelected = state.gatewayBareMode
        enableKnowledgeLayerCheckbox.isSelected = state.enableKnowledgeLayer
        enableWikiLibrarianCheckbox.isSelected = state.enableWikiLibrarian
        enableWorkspaceCheckbox.isSelected = state.enableWorkspace
        autoUpdateWikiCheckbox.isSelected = state.autoUpdateWiki
        enableDreamWikiMaintenanceCheckbox.isSelected = state.enableDreamWikiMaintenance
        dreamWikiMinElapsedHoursField.text = state.dreamWikiMinElapsedHours.toString()
        dreamWikiMinSignalUnitsField.text = state.dreamWikiMinSignalUnits.toString()
        dreamWikiScanThrottleMinutesField.text = state.dreamWikiScanThrottleMinutes.toString()
        selectProfilingBackend(state.profilingBackendPreference)
        profilingSamplingIntervalField.text = state.profilingSamplingIntervalMs.toString()
        profilingMaxDurationField.text = state.profilingMaxDurationSeconds.toString()
        profilingMaxRecordingMbField.text = state.profilingMaxRecordingMb.toString()
        profilingStackDepthField.text = state.profilingStackDepth.toString()
        profilingMaxRecordingsField.text = state.profilingMaxRecordings.toString()
        profilingMaxStorageGbField.text = state.profilingMaxStorageGb.toString()
        profilingAutoAnalyzeCheckbox.isSelected = state.profilingAutoAnalyze
        profilingTopNField.text = state.profilingTopN.toString()
        updateKnowledgeLayerEnabledState()
        showProviderCard()
        updateApiKeyLabel()
    }

    fun applyTo(state: ClawDEASettings.State) {
        val settings = ClawDEASettings.getInstance()
        state.apiProvider = selectedProviderKey()
        settings.setApiKey(effectiveApiKey())
        state.cliPath = cliPathField.text
        state.completionsEnabled = completionsEnabledCheckbox.isSelected
        state.completionsModel = selectedCompletionsModelKey()
        state.completionsDebounceMs = completionsDebounceField.text.toIntOrNull() ?: 300
        state.toolApprovalMode = ToolApprovalModeUi.keyForIndex(toolApprovalCombo.selectedIndex)
        state.autoAcceptEdits = autoAcceptEditsCheckbox.isSelected
        state.bedrockRegion = bedrockRegionField.text
        settings.setBedrockBearerToken(String(bedrockBearerTokenField.password))
        state.vertexRegion = vertexRegionField.text
        state.vertexProjectId = vertexProjectIdField.text
        state.completionTokenBudget = completionTokenBudgetField.text.toIntOrNull() ?: 2048
        state.chatTokenBudget = chatTokenBudgetField.text.toIntOrNull() ?: 16384
        state.actionTokenBudget = actionTokenBudgetField.text.toIntOrNull() ?: 4096
        state.cliExtraArgs = cliExtraArgsField.text
        state.cliEnvScript = cliEnvScriptField.text
        state.enablePsiCollector = enablePsiCollectorCheckbox.isSelected
        state.enableGitCollector = enableGitCollectorCheckbox.isSelected
        state.preloadSkillCatalog = preloadSkillCatalogCheckbox.isSelected
        state.gatewayBareMode = gatewayBareModeCheckbox.isSelected
        state.enableKnowledgeLayer = enableKnowledgeLayerCheckbox.isSelected
        state.enableWikiLibrarian = enableWikiLibrarianCheckbox.isSelected
        state.enableWorkspace = enableWorkspaceCheckbox.isSelected
        state.autoUpdateWiki = autoUpdateWikiCheckbox.isSelected
        state.enableDreamWikiMaintenance = enableDreamWikiMaintenanceCheckbox.isSelected
        state.dreamWikiMinElapsedHours = parseIntField(dreamWikiMinElapsedHoursField, DreamWikiSettingsParser::minElapsedHours)
        state.dreamWikiMinSignalUnits = parseIntField(dreamWikiMinSignalUnitsField, DreamWikiSettingsParser::minSignalUnits)
        state.dreamWikiScanThrottleMinutes = parseIntField(dreamWikiScanThrottleMinutesField, DreamWikiSettingsParser::scanThrottleMinutes)
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

    fun isModifiedFrom(state: ClawDEASettings.State): Boolean {
        val settings = ClawDEASettings.getInstance()
        return selectedProviderKey() != state.apiProvider ||
            effectiveApiKey() != settings.getApiKey() ||
            cliPathField.text != state.cliPath ||
            completionsEnabledCheckbox.isSelected != state.completionsEnabled ||
            selectedCompletionsModelKey() != state.completionsModel ||
            completionsDebounceField.text != state.completionsDebounceMs.toString() ||
            ToolApprovalModeUi.keyForIndex(toolApprovalCombo.selectedIndex) != state.toolApprovalMode ||
            autoAcceptEditsCheckbox.isSelected != state.autoAcceptEdits ||
            bedrockRegionField.text != state.bedrockRegion ||
            String(bedrockBearerTokenField.password) != settings.getBedrockBearerToken() ||
            vertexRegionField.text != state.vertexRegion ||
            vertexProjectIdField.text != state.vertexProjectId ||
            completionTokenBudgetField.text != state.completionTokenBudget.toString() ||
            chatTokenBudgetField.text != state.chatTokenBudget.toString() ||
            actionTokenBudgetField.text != state.actionTokenBudget.toString() ||
            cliExtraArgsField.text != state.cliExtraArgs ||
            cliEnvScriptField.text != state.cliEnvScript ||
            enablePsiCollectorCheckbox.isSelected != state.enablePsiCollector ||
            enableGitCollectorCheckbox.isSelected != state.enableGitCollector ||
            preloadSkillCatalogCheckbox.isSelected != state.preloadSkillCatalog ||
            gatewayBareModeCheckbox.isSelected != state.gatewayBareMode ||
            enableKnowledgeLayerCheckbox.isSelected != state.enableKnowledgeLayer ||
            enableWikiLibrarianCheckbox.isSelected != state.enableWikiLibrarian ||
            enableWorkspaceCheckbox.isSelected != state.enableWorkspace ||
            autoUpdateWikiCheckbox.isSelected != state.autoUpdateWiki ||
            enableDreamWikiMaintenanceCheckbox.isSelected != state.enableDreamWikiMaintenance ||
            normalizedIntField(dreamWikiMinElapsedHoursField, DreamWikiSettingsParser::minElapsedHours) != state.dreamWikiMinElapsedHours ||
            normalizedIntField(dreamWikiMinSignalUnitsField, DreamWikiSettingsParser::minSignalUnits) != state.dreamWikiMinSignalUnits ||
            normalizedIntField(dreamWikiScanThrottleMinutesField, DreamWikiSettingsParser::scanThrottleMinutes) != state.dreamWikiScanThrottleMinutes ||
            selectedProfilingBackendKey() != state.profilingBackendPreference ||
            profilingSamplingIntervalField.text != state.profilingSamplingIntervalMs.toString() ||
            profilingMaxDurationField.text != state.profilingMaxDurationSeconds.toString() ||
            profilingMaxRecordingMbField.text != state.profilingMaxRecordingMb.toString() ||
            profilingStackDepthField.text != state.profilingStackDepth.toString() ||
            profilingMaxRecordingsField.text != state.profilingMaxRecordings.toString() ||
            profilingMaxStorageGbField.text != state.profilingMaxStorageGb.toString() ||
            profilingAutoAnalyzeCheckbox.isSelected != state.profilingAutoAnalyze ||
            profilingTopNField.text != state.profilingTopN.toString()
    }

    fun getPreferredFocusedComponent(): JComponent = apiProviderCombo

    fun disposeCard() {
        com.intellij.openapi.util.Disposer.dispose(subscriptionCard)
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

    private fun updateKnowledgeLayerEnabledState() {
        val knowledgeEnabled = enableKnowledgeLayerCheckbox.isSelected
        val dreamEnabled = knowledgeEnabled && enableDreamWikiMaintenanceCheckbox.isSelected
        enableWikiLibrarianCheckbox.isEnabled = knowledgeEnabled
        enableWorkspaceCheckbox.isEnabled = knowledgeEnabled
        autoUpdateWikiCheckbox.isEnabled = knowledgeEnabled
        enableDreamWikiMaintenanceCheckbox.isEnabled = knowledgeEnabled
        dreamWikiMinElapsedHoursField.isEnabled = dreamEnabled
        dreamWikiMinSignalUnitsField.isEnabled = dreamEnabled
        dreamWikiScanThrottleMinutesField.isEnabled = dreamEnabled
    }

    private fun parseIntField(field: JBTextField, parser: (String) -> Int): Int =
        normalizedIntField(field, parser).also { field.text = it.toString() }

    private fun normalizedIntField(field: JBTextField, parser: (String) -> Int): Int =
        parser(field.text)

    private fun selectedProfilingBackendKey(): String {
        val idx = profilingBackendCombo.selectedIndex
        return if (idx >= 0) BACKEND_KEYS[idx] else "auto"
    }

    private fun selectProfilingBackend(key: String) {
        val idx = BACKEND_KEYS.indexOf(key)
        profilingBackendCombo.selectedIndex = if (idx >= 0) idx else 0
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
