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
// src/main/kotlin/com/adobe/clawdea/settings/tabs/KnowledgeLayerTab.kt
package com.adobe.clawdea.settings.tabs

import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/** Knowledge layer section extracted from the flat settings panel. */
class KnowledgeLayerTab : SettingsTab {

    override val title: String = "Knowledge"

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

    override val component: JComponent = FormBuilder.createFormBuilder()
        .addComponent(enableKnowledgeLayerCheckbox, 1)
        .addComponent(enableWikiLibrarianCheckbox, 2)
        .addComponent(enableWorkspaceCheckbox, 2)
        .addComponent(autoUpdateWikiCheckbox, 2)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    init {
        enableKnowledgeLayerCheckbox.addItemListener { updateEnabledState() }
        updateEnabledState()
    }

    private fun updateEnabledState() {
        val knowledgeEnabled = enableKnowledgeLayerCheckbox.isSelected
        enableWikiLibrarianCheckbox.isEnabled = knowledgeEnabled
        enableWorkspaceCheckbox.isEnabled = knowledgeEnabled
        autoUpdateWikiCheckbox.isEnabled = knowledgeEnabled
    }

    override fun loadFrom(state: ClawDEASettings.State) {
        enableKnowledgeLayerCheckbox.isSelected = state.enableKnowledgeLayer
        enableWikiLibrarianCheckbox.isSelected = state.enableWikiLibrarian
        enableWorkspaceCheckbox.isSelected = state.enableWorkspace
        autoUpdateWikiCheckbox.isSelected = state.autoUpdateWiki
        updateEnabledState()
    }

    override fun applyTo(state: ClawDEASettings.State) {
        state.enableKnowledgeLayer = enableKnowledgeLayerCheckbox.isSelected
        state.enableWikiLibrarian = enableWikiLibrarianCheckbox.isSelected
        state.enableWorkspace = enableWorkspaceCheckbox.isSelected
        state.autoUpdateWiki = autoUpdateWikiCheckbox.isSelected
    }

    override fun isModifiedFrom(state: ClawDEASettings.State): Boolean =
        enableKnowledgeLayerCheckbox.isSelected != state.enableKnowledgeLayer ||
            enableWikiLibrarianCheckbox.isSelected != state.enableWikiLibrarian ||
            enableWorkspaceCheckbox.isSelected != state.enableWorkspace ||
            autoUpdateWikiCheckbox.isSelected != state.autoUpdateWiki
}
