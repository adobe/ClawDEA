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
// src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettingsConfigurable.kt
package com.adobe.clawdea.settings

import com.adobe.clawdea.gateway.ModelCatalogListener
import com.adobe.clawdea.gateway.ModelSelectorProbeStarter
import com.adobe.clawdea.settings.tabs.AdvancedTab
import com.adobe.clawdea.settings.tabs.KnowledgeLayerTab
import com.adobe.clawdea.settings.tabs.ProfilingTab
import com.adobe.clawdea.settings.tabs.ProvidersTab
import com.adobe.clawdea.settings.tabs.SettingsTab
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTabbedPane
import javax.swing.JComponent

class ClawDEASettingsConfigurable : Configurable {

    private var providersTab: ProvidersTab? = null
    private var tabs: List<SettingsTab> = emptyList()

    override fun getDisplayName(): String = "ClawDEA"

    override fun createComponent(): JComponent {
        val providers = ProvidersTab()
        providersTab = providers
        val orderedTabs = listOf(
            providers,
            KnowledgeLayerTab(),
            ProfilingTab(),
            AdvancedTab(),
        )
        tabs = orderedTabs

        val state = ClawDEASettings.getInstance().state
        val tabbedPane = JBTabbedPane()
        for (tab in orderedTabs) {
            tab.loadFrom(state)
            tabbedPane.addTab(tab.title, tab.component)
        }
        return tabbedPane
    }

    override fun getPreferredFocusedComponent(): JComponent? =
        providersTab?.getPreferredFocusedComponent()

    override fun isModified(): Boolean {
        val state = ClawDEASettings.getInstance().state
        return tabs.any { it.isModifiedFrom(state) }
    }

    override fun apply() {
        val state = ClawDEASettings.getInstance().state
        val oldProvider = state.apiProvider
        tabs.forEach { it.applyTo(state) }
        if (state.apiProvider != oldProvider) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(ModelCatalogListener.TOPIC)
                .onCatalogUpdated()
            ModelSelectorProbeStarter.runProbe()
        }
        ApplicationManager.getApplication().messageBus
            .syncPublisher(SettingsChangedListener.TOPIC)
            .onSettingsChanged()
    }

    override fun reset() {
        val state = ClawDEASettings.getInstance().state
        tabs.forEach { it.loadFrom(state) }
    }

    override fun disposeUIResources() {
        providersTab?.dispose()
        providersTab = null
        tabs = emptyList()
    }
}
