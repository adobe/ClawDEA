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

import com.adobe.clawdea.auth.AuthManager
import com.adobe.clawdea.auth.CodexSubscriptionAuthEventListener
import com.adobe.clawdea.auth.SubscriptionAuthEventListener
import com.adobe.clawdea.gateway.ModelCatalogListener
import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.provider.AgentSelection
import com.adobe.clawdea.provider.ProviderRegistry
import com.adobe.clawdea.provider.openai.profile.ProfileStore
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
 * Manages the combined provider+model selector combo box: rendering, persistence, action
 * listener, and message-bus subscriptions for catalog/auth changes.
 *
 * Each entry is a [ProviderModelOption] (a provider › model row). Picking an option updates the
 * tab's [AgentSelection] and routes to one of three effects via [rebuildActionFor]:
 * - [RebuildAction.NONE] — no change.
 * - [RebuildAction.RESTART] — same provider+profile, model-only change: restart the bridge in place
 *   (the bridge re-reads its model on restart).
 * - [RebuildAction.REBUILD_SESSION] — provider, profile, or backend-kind change: rebuild the tab's
 *   [ChatSession] carrying the picked selection (backend kind is fixed per bridge).
 *
 * Extracted from [ChatPanel] to reduce its size.
 */
class ModelComboManager(
    private val project: com.intellij.openapi.project.Project,
    private val modelCombo: JComboBox<ProviderModelOption>,
    parentDisposable: Disposable,
    private val isBridgeAvailable: () -> Boolean,
    private val currentSelection: () -> AgentSelection,
    private val restartBridge: () -> Unit,
    private val rebuildSession: (AgentSelection) -> Unit,
    private val appendInfo: (String) -> Unit,
    private val appendError: (String) -> Unit,
) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(ModelComboManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var suppressEvents: Boolean = false

    init {
        modelCombo.font = modelCombo.font.deriveFont(11f)
        modelCombo.renderer = object : SimpleListCellRenderer<ProviderModelOption>() {
            init {
                font = modelCombo.font.deriveFont(11f)
            }

            override fun customize(
                list: JList<out ProviderModelOption>,
                value: ProviderModelOption?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                text = value?.label.orEmpty()
                // Disabled (unauthenticated) rows render greyed and are non-selectable.
                isEnabled = value?.enabled ?: true
                if (value?.enabled == false) {
                    foreground = com.intellij.util.ui.UIUtil.getLabelDisabledForeground()
                }
            }
        }
        modelCombo.putClientProperty("JComponent.sizeVariant", "small")

        modelCombo.addActionListener {
            if (suppressEvents) return@addActionListener
            val option = modelCombo.selectedItem as? ProviderModelOption ?: return@addActionListener
            // A disabled (sign-in) row is not a valid pick: revert to the current tab selection.
            if (!option.enabled) {
                reselectCurrent()
                return@addActionListener
            }
            onOptionPicked(option)
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

        // Rebuild the combo when a turn is observed (live or resume), so the Default-derived label
        // and any newly-authenticated provider are reflected.
        project.messageBus.connect(parentDisposable).subscribe(
            com.adobe.clawdea.cost.CostSnapshotListener.TOPIC,
            object : com.adobe.clawdea.cost.CostSnapshotListener {
                private var lastSeenResolved: String? = null
                override fun onCostChanged() {
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

    /** Handle a valid (enabled) option pick: persist, update tab selection, restart or rebuild. */
    private fun onOptionPicked(option: ProviderModelOption) {
        val current = currentSelection()
        val picked = option.selection
        val action = rebuildActionFor(current, picked)

        // Persist the model selection keyed by the picked provider/profile catalog key, so a later
        // refresh() re-selects it and the rebuilt/restarted bridge reads the same model.
        ClawDEASettings.getInstance().setSelectedModelId(
            project.basePath.orEmpty(),
            picked.modelId,
            providerId = picked.catalogKey(),
        )

        when (action) {
            RebuildAction.NONE -> Unit
            RebuildAction.RESTART -> {
                if (isBridgeAvailable()) {
                    scope.launch {
                        try {
                            restartBridge()
                            withContext(Dispatchers.Main) { appendInfo("Switched to ${option.label}") }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.warn("failed to restart bridge after model switch", e)
                            withContext(Dispatchers.Main) { appendError("Failed to switch model: ${e.message}") }
                        }
                    }
                } else {
                    appendInfo("Model set to ${option.label} (applied on next send)")
                }
            }
            RebuildAction.REBUILD_SESSION -> {
                // Backend kind / provider / profile change: rebuild the tab's session carrying the
                // picked selection (must run on the EDT — it swaps tool-window content).
                ApplicationManager.getApplication().invokeLater(
                    { rebuildSession(picked) },
                    ModalityState.any(),
                )
            }
        }
    }

    private fun reselectCurrent() {
        val current = currentSelection()
        val model = modelCombo.model
        suppressEvents = true
        try {
            val idx = (0 until model.size).firstOrNull { i ->
                model.getElementAt(i)?.selection == current
            } ?: (0 until model.size).firstOrNull { i ->
                model.getElementAt(i)?.enabled == true
            } ?: -1
            if (idx >= 0) modelCombo.selectedIndex = idx
        } finally {
            suppressEvents = false
        }
    }

    fun refresh() {
        val options = buildChatOptions(collectSources())
        val current = currentSelection()
        suppressEvents = true
        try {
            modelCombo.model = DefaultComboBoxModel(options.toTypedArray())
            // Re-select the row matching the current tab selection; otherwise first enabled row.
            val idx = options.indexOfFirst { it.selection == current }
                .let { if (it >= 0) it else options.indexOfFirst { o -> o.enabled } }
            if (idx >= 0) modelCombo.selectedIndex = idx
        } finally {
            suppressEvents = false
        }
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

    companion object {

        /**
         * Effect of switching the tab's [AgentSelection] from [current] to [picked]:
         * - identical (providerId + profileId + modelId all equal) → [RebuildAction.NONE].
         * - same providerId AND same profileId, only modelId differs → [RebuildAction.RESTART]
         *   (bridge keeps its backend and re-reads the model on restart).
         * - providerId differs, profileId differs, or the backend kind differs →
         *   [RebuildAction.REBUILD_SESSION] (backend kind is fixed per bridge, so a new session/bridge
         *   must be built carrying the picked selection).
         */
        fun rebuildActionFor(current: AgentSelection, picked: AgentSelection): RebuildAction {
            if (current == picked) return RebuildAction.NONE
            val sameProvider = current.providerId == picked.providerId
            val sameProfile = current.profileId == picked.profileId
            val sameBackendKind =
                ProviderRegistry.require(current.providerId).backendKind ==
                    ProviderRegistry.require(picked.providerId).backendKind
            return if (sameProvider && sameProfile && sameBackendKind) {
                RebuildAction.RESTART
            } else {
                RebuildAction.REBUILD_SESSION
            }
        }

        /**
         * The models offered in the chat dropdown for [effectiveProviderId]. For the
         * openai-compatible provider only tool-capable (capability=="agentic") AND enabled models are
         * chat-usable (the backend requires AGENTIC to start a chat), so completion-only/unknown or
         * disabled rows are filtered out. For every other provider (Claude/Codex) the catalog carries
         * no per-model capability meaning and is returned unfiltered.
         */
        fun chatSelectableModels(
            catalog: List<ModelEntry>,
            effectiveProviderId: String,
        ): List<ModelEntry> {
            if (effectiveProviderId != ProviderRegistry.OPENAI_COMPATIBLE_ID) {
                return catalog
            }
            return catalog.filter { it.capability == "agentic" && it.enabled }
        }

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

    /** The effect of picking a new [AgentSelection] for a chat tab. */
    enum class RebuildAction { NONE, RESTART, REBUILD_SESSION }
}
