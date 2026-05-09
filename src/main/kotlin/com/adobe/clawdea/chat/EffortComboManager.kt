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
package com.adobe.clawdea.chat

import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.*
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox

/**
 * Manages the effort selector combo box: rendering, persistence, and
 * action listener. Mirrors [ModelComboManager] but with a static enum
 * (no catalog probes, no message-bus subscriptions).
 *
 * Storage holds the lowercase flag string accepted by `claude --effort`
 * (`low`, `medium`, `high`, `xhigh`, `max`). The "Default" entry maps
 * to an empty string so no `--effort` flag is passed.
 */
class EffortComboManager(
    private val project: com.intellij.openapi.project.Project,
    private val effortCombo: JComboBox<EffortEntry>,
    parentDisposable: Disposable,
    private val isBridgeAvailable: () -> Boolean,
    private val restartBridge: () -> Unit,
    private val appendInfo: (String) -> Unit,
    private val appendError: (String) -> Unit,
) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(EffortComboManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var suppressEvents: Boolean = false

    init {
        effortCombo.font = effortCombo.font.deriveFont(11f)
        effortCombo.toolTipText =
            "Thinking effort for this session. Higher levels only take effect on " +
                "models with extended thinking; lower levels are silently capped on " +
                "others. Changes restart the CLI."
        effortCombo.putClientProperty("JComponent.sizeVariant", "small")

        effortCombo.addActionListener {
            if (suppressEvents) return@addActionListener
            val entry = effortCombo.selectedItem as? EffortEntry ?: return@addActionListener
            ClawDEASettings.getInstance().setSelectedEffort(
                project.basePath.orEmpty(),
                entry.flagValue,
            )
            if (isBridgeAvailable()) {
                scope.launch {
                    try {
                        restartBridge()
                        withContext(Dispatchers.Main) { appendInfo("Effort set to ${entry.displayName}") }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("failed to restart bridge after effort switch", e)
                        withContext(Dispatchers.Main) { appendError("Failed to switch effort: ${e.message}") }
                    }
                }
            } else {
                appendInfo("Effort set to ${entry.displayName} (applied on next send)")
            }
        }

        Disposer.register(parentDisposable) { scope.cancel() }
        refresh()
    }

    fun refresh() {
        val settings = ClawDEASettings.getInstance()
        val selected = settings.getSelectedEffort(project.basePath.orEmpty())
        suppressEvents = true
        try {
            effortCombo.model = DefaultComboBoxModel(ENTRIES.toTypedArray())
            effortCombo.selectedIndex =
                ENTRIES.indexOfFirst { it.flagValue == selected }.coerceAtLeast(0)
        } finally {
            suppressEvents = false
        }
    }

    data class EffortEntry(val flagValue: String, val displayName: String) {
        override fun toString(): String = displayName
    }

    companion object {
        val ENTRIES: List<EffortEntry> = listOf(
            EffortEntry(flagValue = "",       displayName = "Default"),
            EffortEntry(flagValue = "low",    displayName = "Low"),
            EffortEntry(flagValue = "medium", displayName = "Medium"),
            EffortEntry(flagValue = "high",   displayName = "High"),
            EffortEntry(flagValue = "xhigh",  displayName = "xHigh"),
            EffortEntry(flagValue = "max",    displayName = "Max"),
        )
    }
}
