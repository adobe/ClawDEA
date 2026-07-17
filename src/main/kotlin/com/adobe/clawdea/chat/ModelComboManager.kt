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

import com.adobe.clawdea.auth.CodexSubscriptionAuthEventListener
import com.adobe.clawdea.auth.SubscriptionAuthEventListener
import com.adobe.clawdea.gateway.ModelCatalogListener
import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SimpleListCellRenderer
import kotlinx.coroutines.*
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JList

/**
 * Manages the model selector combo box: rendering, persistence, action
 * listener, and message-bus subscriptions for catalog/auth changes.
 *
 * Extracted from [ChatPanel] to reduce its size.
 */
class ModelComboManager(
    private val project: com.intellij.openapi.project.Project,
    private val modelCombo: JComboBox<ModelEntry>,
    parentDisposable: Disposable,
    private val isBridgeAvailable: () -> Boolean,
    private val restartBridge: () -> Unit,
    private val appendInfo: (String) -> Unit,
    private val appendError: (String) -> Unit,
) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(ModelComboManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var suppressEvents: Boolean = false

    init {
        modelCombo.font = modelCombo.font.deriveFont(11f)
        modelCombo.renderer = object : SimpleListCellRenderer<ModelEntry>() {
            init {
                font = modelCombo.font.deriveFont(11f)
            }

            override fun customize(
                list: JList<out ModelEntry>,
                value: ModelEntry?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                text = value?.displayName.orEmpty()
            }
        }
        modelCombo.putClientProperty("JComponent.sizeVariant", "small")

        modelCombo.addActionListener {
            if (suppressEvents) return@addActionListener
            val entry = modelCombo.selectedItem as? ModelEntry ?: return@addActionListener
            ClawDEASettings.getInstance().setSelectedModelId(
                project.basePath.orEmpty(),
                entry.id,
                providerId = com.adobe.clawdea.auth.AuthManager.getInstance().effectiveCatalogKey(),
            )
            if (isBridgeAvailable()) {
                scope.launch {
                    try {
                        restartBridge()
                        withContext(Dispatchers.Main) { appendInfo("Switched to ${entry.displayName}") }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("failed to restart bridge after model switch", e)
                        withContext(Dispatchers.Main) { appendError("Failed to switch model: ${e.message}") }
                    }
                }
            } else {
                appendInfo("Model set to ${entry.displayName} (applied on next send)")
            }
        }

        val connection = ApplicationManager.getApplication().messageBus.connect(parentDisposable)
        connection.subscribe(
            ModelCatalogListener.TOPIC,
            object : ModelCatalogListener {
                override fun onCatalogUpdated() {
                    ApplicationManager.getApplication()
                        .invokeLater({ refresh() }, ModalityState.any())
                }
            },
        )
        connection.subscribe(
            SubscriptionAuthEventListener.TOPIC,
            object : SubscriptionAuthEventListener {
                override fun onAuthFailed(reason: String) {
                    ApplicationManager.getApplication().invokeLater(
                        {
                            appendError(
                                "Subscription credentials invalid: $reason. " +
                                "Open Settings > Tools > ClawDEA and click Re-authenticate."
                            )
                        },
                        ModalityState.any(),
                    )
                }
            },
        )
        // Codex (OpenAI ChatGPT subscription) is on a distinct topic; subscribe here too so a
        // codex auth failure surfaces the same re-authenticate hint (see CodexAppServerProcess).
        connection.subscribe(
            CodexSubscriptionAuthEventListener.TOPIC,
            object : CodexSubscriptionAuthEventListener {
                override fun onAuthFailed(reason: String) {
                    ApplicationManager.getApplication().invokeLater(
                        {
                            appendError(
                                "ChatGPT subscription credentials invalid: $reason. " +
                                "Open Settings > Tools > ClawDEA and click Re-authenticate."
                            )
                        },
                        ModalityState.any(),
                    )
                }
            },
        )

        // Re-label "Default (<model>)" when a turn is observed (live or resume).
        project.messageBus.connect(parentDisposable).subscribe(
            com.adobe.clawdea.cost.CostSnapshotListener.TOPIC,
            object : com.adobe.clawdea.cost.CostSnapshotListener {
                private var lastSeenResolved: String? = null
                override fun onCostChanged() {
                    // Only refresh when the resolved Default model actually changed, to
                    // avoid rebuilding the combo on every cost tick.
                    val resolved = com.adobe.clawdea.cost.CostTracker.getInstance(project).defaultResolvedModel()
                    if (resolved != lastSeenResolved) {
                        lastSeenResolved = resolved
                        ApplicationManager.getApplication().invokeLater({ refresh() }, ModalityState.any())
                    }
                }
            },
        )

        Disposer.register(parentDisposable) { scope.cancel() }
        refresh()
    }

    fun refresh() {
        val settings = ClawDEASettings.getInstance()
        val state = settings.state
        val catalogKey = com.adobe.clawdea.auth.AuthManager.getInstance().effectiveCatalogKey()
        val selectedId = settings.getSelectedModelId(project.basePath.orEmpty(), catalogKey)
        val catalog = (state.modelCatalogs[catalogKey] ?: emptyList())
        // Annotate "Default" only with the model a Default-selection turn actually
        // resolved to (never an explicit pick or stale history). Project-wide value.
        val resolved = com.adobe.clawdea.cost.CostTracker.getInstance(project).defaultResolvedModel()
        val entries = mutableListOf(ModelEntry(id = "", displayName = defaultLabel(resolved, catalog)))
        entries += catalog.map { it.copy() }
        suppressEvents = true
        try {
            modelCombo.model = DefaultComboBoxModel(entries.toTypedArray())
            modelCombo.selectedIndex = entries.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        } finally {
            suppressEvents = false
        }
    }

    companion object {
        val DEFAULT_SENTINEL = ModelEntry(id = "", displayName = "Default")

        /**
         * Label for the "Default" entry, annotated with the model "Default" actually
         * resolved to once a turn has been observed: "Default (Opus 4.8)". Falls back
         * to a prettified id when the model isn't in the catalog, and to plain "Default"
         * when no model has been observed yet.
         */
        fun defaultLabel(observedModelId: String?, catalog: List<ModelEntry>): String {
            if (observedModelId.isNullOrBlank()) return "Default"
            val friendly = catalog.firstOrNull { it.id == observedModelId }?.displayName
                ?.removePrefix("Claude ")
                ?: prettyModelName(observedModelId)
            return "Default ($friendly)"
        }

        /** "claude-opus-4-8" -> "Opus 4.8"; "us.anthropic.claude-sonnet-4-6" -> "Sonnet 4.6". */
        internal fun prettyModelName(id: String): String {
            if (id.startsWith("gpt-")) {
                // gpt-5-codex -> "GPT-5 Codex", gpt-5-mini -> "GPT-5 mini", gpt-5 -> "GPT-5"
                val rest = id.removePrefix("gpt-")
                val parts = rest.split("-")
                val version = parts.first()
                val suffix = parts.drop(1).joinToString(" ") { seg ->
                    when (seg) {
                        "codex" -> "Codex"
                        "mini"  -> "mini"
                        "nano"  -> "nano"
                        else    -> seg.replaceFirstChar { it.uppercase() }
                    }
                }
                return if (suffix.isBlank()) "GPT-$version" else "GPT-$version $suffix"
            }
            val core = id.substringAfter("claude-", id)
            val words = core.split('-').filter { it.isNotBlank() }
            if (words.isEmpty()) return id
            val name = words.first().replaceFirstChar { it.uppercase() }
            val version = words.drop(1).joinToString(".")
            return if (version.isBlank()) name else "$name $version"
        }
    }
}
