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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class ClawDEASettingsConfigurable : Configurable {

    private var panel: ClawDEASettingsPanel? = null

    override fun getDisplayName(): String = "ClawDEA"

    override fun createComponent(): JComponent {
        val p = ClawDEASettingsPanel()
        panel = p
        return p.panel
    }

    override fun getPreferredFocusedComponent(): JComponent? =
        panel?.getPreferredFocusedComponent()

    override fun isModified(): Boolean {
        val state = ClawDEASettings.getInstance().state
        val p = panel ?: return false
        return p.isModifiedFrom(state) || p.isModelsModified(state.modelCatalogs)
    }

    override fun apply() {
        val state = ClawDEASettings.getInstance().state
        val p = panel ?: return
        val oldProvider = state.apiProvider
        p.applyTo(state)
        state.modelCatalogs = p.saveModels()
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
        val p = panel ?: return
        p.loadFrom(state)
        p.loadModels(state.modelCatalogs)
    }

    override fun disposeUIResources() {
        panel?.disposeCard()
        panel = null
    }
}
