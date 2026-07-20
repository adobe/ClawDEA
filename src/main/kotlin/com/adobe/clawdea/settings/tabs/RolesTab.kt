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
package com.adobe.clawdea.settings.tabs

import com.adobe.clawdea.auth.AuthManager
import com.adobe.clawdea.chat.ProviderModelOption
import com.adobe.clawdea.chat.ProviderModelSource
import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.AgentRole
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.ProviderRegistry
import com.adobe.clawdea.provider.openai.profile.ProfileStore
import com.adobe.clawdea.settings.ClawDEASettings
import com.adobe.clawdea.provider.RoleSelectionStore
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.awt.Color
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

/**
 * Settings tab for role-specific provider+model configuration (Chat default / Wiki / Completions).
 * Each role has a picker offering all providers' models (completion-only included). The Wiki picker
 * displays a warning when a completion-only model is chosen (wiki authoring needs tool support).
 */
class RolesTab : SettingsTab {
    override val title: String = "Roles"

    private val chatCombo = ComboBox<ProviderModelOption>()
    private val wikiCombo = ComboBox<ProviderModelOption>()
    private val completionsCombo = ComboBox<ProviderModelOption>()

    private val wikiWarning = JBLabel("⚠ This model may not support tools; wiki authoring needs a tool-capable model.").apply {
        foreground = Color(249, 226, 175) // yellow
        font = font.deriveFont(11f)
        isVisible = false
    }

    override val component: JComponent by lazy {
        setupCombos()
        FormBuilder.createFormBuilder()
            .addLabeledComponent("Chat default:", chatCombo)
            .addLabeledComponent("Wiki:", wikiCombo)
            .addComponent(wikiWarning)
            .addLabeledComponent("Completions:", completionsCombo)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun setupCombos() {
        listOf(chatCombo, wikiCombo, completionsCombo).forEach { combo ->
            combo.font = combo.font.deriveFont(11f)
            combo.renderer = object : SimpleListCellRenderer<ProviderModelOption>() {
                init {
                    font = combo.font.deriveFont(11f)
                }

                override fun customize(
                    list: JList<out ProviderModelOption>,
                    value: ProviderModelOption?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    text = value?.label.orEmpty()
                    isEnabled = value?.enabled ?: true
                    if (value?.enabled == false) {
                        foreground = com.intellij.util.ui.UIUtil.getLabelDisabledForeground()
                    }
                }
            }
            combo.putClientProperty("JComponent.sizeVariant", "small")
        }

        // Update wiki warning live when selection changes
        wikiCombo.addActionListener {
            updateWikiWarning()
        }
    }

    override fun loadFrom(state: ClawDEASettings.State) {
        val store = RoleSelectionStore(ClawDEASettings.getInstance())
        val options = buildRoleOptions(collectSources())

        // Load each role's selection and populate combos
        loadComboFor(chatCombo, store.get(AgentRole.CHAT_DEFAULT), options)
        loadComboFor(wikiCombo, store.get(AgentRole.WIKI), options)
        loadComboFor(completionsCombo, store.get(AgentRole.COMPLETIONS), options)

        updateWikiWarning()
    }

    override fun applyTo(state: ClawDEASettings.State) {
        val store = RoleSelectionStore(ClawDEASettings.getInstance())
        (chatCombo.selectedItem as? ProviderModelOption)?.selection?.let {
            store.set(AgentRole.CHAT_DEFAULT, it)
        }
        (wikiCombo.selectedItem as? ProviderModelOption)?.selection?.let {
            store.set(AgentRole.WIKI, it)
        }
        (completionsCombo.selectedItem as? ProviderModelOption)?.selection?.let {
            store.set(AgentRole.COMPLETIONS, it)
        }
    }

    override fun isModifiedFrom(state: ClawDEASettings.State): Boolean {
        val store = RoleSelectionStore(ClawDEASettings.getInstance())
        val chatStored = store.get(AgentRole.CHAT_DEFAULT)
        val wikiStored = store.get(AgentRole.WIKI)
        val completionsStored = store.get(AgentRole.COMPLETIONS)

        val chatCurrent = (chatCombo.selectedItem as? ProviderModelOption)?.selection
        val wikiCurrent = (wikiCombo.selectedItem as? ProviderModelOption)?.selection
        val completionsCurrent = (completionsCombo.selectedItem as? ProviderModelOption)?.selection

        return chatCurrent != chatStored || wikiCurrent != wikiStored || completionsCurrent != completionsStored
    }

    private fun loadComboFor(combo: ComboBox<ProviderModelOption>, selection: AgentSelection, options: List<ProviderModelOption>) {
        combo.model = DefaultComboBoxModel(options.toTypedArray())
        val idx = options.indexOfFirst { it.selection == selection }
            .let { if (it >= 0) it else options.indexOfFirst { o -> o.enabled } }
        if (idx >= 0) combo.selectedIndex = idx
    }

    private fun updateWikiWarning() {
        val option = wikiCombo.selectedItem as? ProviderModelOption
        val modelId = option?.selection?.modelId
        if (modelId == null) {
            wikiWarning.isVisible = false
            return
        }

        // Look up the model entry to check capability
        val catalogKey = option.selection.catalogKey()
        val catalog = ClawDEASettings.getInstance().state.modelCatalogs[catalogKey] ?: emptyList()
        val modelEntry = catalog.firstOrNull { it.id == modelId }

        wikiWarning.isVisible = computeCapabilityWarning(AgentRole.WIKI, modelEntry, option.selection)
    }

    /**
     * Build the [ProviderModelSource] list from every provider ClawDEA supports. For the
     * openai-compatible provider, one source per imported profile (each with its own catalog and
     * authentication state); for every other provider, one source keyed by its bare provider id.
     */
    private fun collectSources(): List<ProviderModelSource> {
        val settings = ClawDEASettings.getInstance()
        val state = settings.state
        val auth = AuthManager.getInstance()
        val sources = mutableListOf<ProviderModelSource>()

        for (descriptor in ProviderRegistry.all()) {
            if (descriptor.id == ProviderRegistry.OPENAI_COMPATIBLE_ID) {
                // One source per imported profile.
                for (profile in ProfileStore(settings).profiles()) {
                    val sel = AgentSelection(descriptor.id, profile.id, "")
                    val catalogKey = ProviderRegistry.catalogKey(descriptor.id, profile.id)
                    sources += ProviderModelSource(
                        providerId = descriptor.id,
                        profileId = profile.id,
                        displayLabel = profile.name.ifBlank { descriptor.displayLabel },
                        authenticated = auth.isAuthenticated(sel),
                        models = state.modelCatalogs[catalogKey] ?: emptyList(),
                    )
                }
            } else {
                val sel = AgentSelection(descriptor.id, null, "")
                sources += ProviderModelSource(
                    providerId = descriptor.id,
                    profileId = null,
                    displayLabel = descriptor.displayLabel,
                    authenticated = auth.isAuthenticated(sel),
                    models = state.modelCatalogs[descriptor.id] ?: emptyList(),
                )
            }
        }
        return sources
    }
}
