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
// src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettings.kt
package com.adobe.clawdea.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.OptionTag
import com.adobe.clawdea.CLAUDE_DIR
import com.adobe.clawdea.gateway.DEFAULT_OPENAI_CATALOG
import com.adobe.clawdea.gateway.DEFAULT_OPENAI_SUBSCRIPTION_CATALOG
import com.adobe.clawdea.gateway.ModelEntry
import com.adobe.clawdea.gateway.defaultModelCatalogsMap
import java.util.concurrent.ConcurrentHashMap

@State(
    name = "com.adobe.clawdea.settings.ClawDEASettings",
    storages = [Storage("ClawDEASettings.xml")]
)
@Service
class ClawDEASettings : PersistentStateComponent<ClawDEASettings.State> {

    data class State(
        // General
        var apiProvider: String = "anthropic", // "anthropic", "bedrock", "vertex"
        var apiKey: String = "",
        var cliPath: String = "claude",
        /** Path to the OpenAI `codex` CLI, resolved via [com.adobe.clawdea.cli.resolveCodexCliPath]. */
        var codexCliPath: String = "codex",
        var completionsEnabled: Boolean = true,
        var completionsModel: String = "sonnet",
        var completionsDebounceMs: Int = 300,
        /**
         * When true, inline completions are only requested when the user
         * explicitly triggers them (the "Trigger Inline Completion" action /
         * hotkey), never automatically while typing or moving the caret. This
         * avoids spending API tokens on incidental edits. Default false keeps
         * the automatic-as-you-type behavior (see issue #146).
         */
        var completionsManualOnly: Boolean = false,
        var defaultChatMode: String = "Auto",
        /** "confirm-all" | "allow-safe" | "allow-all". See docs/superpowers/specs/2026-04-29-permission-approval-ui-design.md. */
        var toolApprovalMode: String = "confirm-all",
        /**
         * When true, McpEditReviewTools auto-applies edits without showing the diff dialog.
         * Independent of [toolApprovalMode] — the MCP propose_edit tool is already
         * trusted, so the gate here is just "do you want to review every edit".
         * Default false (conservative for pre-release); the chat transcript keeps a
         * diff link per accepted edit, so users who enable it can still revert.
         * Serialized as `autoAcceptFileEdits` to avoid colliding with the old,
         * pre-migration `autoAcceptEdits` XML tag (silently ignored on read).
         */
        @OptionTag("autoAcceptFileEdits") var autoAcceptEdits: Boolean = false,
        // Bedrock
        var bedrockRegion: String = "",
        var bedrockBearerToken: String = "",
        // Vertex
        var vertexRegion: String = "",
        var vertexProjectId: String = "",
        // Advanced
        var completionTokenBudget: Int = 512,
        var chatTokenBudget: Int = 16384,
        // OpenAI-compatible agent loop limits. 0 = unlimited (loop runs until the model stops
        // calling tools or the user aborts). Non-zero re-arms an indefinite-wait "continue?"
        // checkpoint every N rounds / after M minutes. See
        // docs/superpowers/specs/2026-07-19-unbounded-agent-loop-and-compaction-design.md.
        var agentMaxToolRounds: Int = 0,
        var agentMaxElapsedMinutes: Int = 0,
        // Fraction of the context budget at which the agent loop compacts (summarizes) history.
        // Applies to the token budget when the model's context window is known, else to CHAR_FALLBACK.
        var agentContextCompactionThreshold: Double = 0.8,
        var actionTokenBudget: Int = 4096,
        var cliExtraArgs: String = "",
        var cliEnvScript: String = "",
        var enablePsiCollector: Boolean = true,
        var enableGitCollector: Boolean = true,
        // Knowledge layer (Phase 1: REPO_STATE + primer)
        var enableKnowledgeLayer: Boolean = true,
        /**
         * When true (default), the primer directive teaches the main agent to delegate
         * project-design questions to the `wiki-librarian` Claude Code subagent via
         * Task, and `search_wiki` is not registered as an MCP tool. When false, the
         * legacy directive ("first call must be a wiki probe via search_wiki") is
         * emitted and `search_wiki` is registered. See
         * docs/specs/2026-05-16-wiki-librarian-subagent-design.md.
         */
        var enableWikiLibrarian: Boolean = true,
        /**
         * Threshold above which REPO_STATE regeneration logs a warning (observability only —
         * does not interrupt the regen). A real timeout would require running the
         * regen on a worker thread with cancellable Future; deferred until the
         * synchronous path proves slow in practice.
         */
        var repoStateWarnThresholdMs: Int = 200,
        var claudeDirName: String = CLAUDE_DIR,
        /** Subdirectory under [claudeDirName] holding the curated wiki. Default "wiki" → `<project>/.claude/wiki/`. */
        var wikiSubdir: String = "wiki",
        /**
         * Auto-update project context wiki. When true, the agent is instructed to
         * write new concept pages directly (silent learning); when false, it must
         * use `propose_write`/`propose_edit` so the user reviews each diff. Default
         * false — explicit user opt-in per the original design brainstorm.
         * The Phase 4 drift detector also honors this flag.
         */
        var autoUpdateWiki: Boolean = false,
        // Knowledge layer (Phase 3: workspace manifest)
        /**
         * Top-level toggle for Phase 3 cross-project context. When true,
         * `SiblingsSource` joins the primer (between `WikiIndexSource` and
         * `RepoStateSource`) and the three workspace MCP tools
         * (`list_workspace_repos`, `read_sibling_wiki`,
         * `read_sibling_repo_state`) are active. Phase 5 will add a
         * Settings UI; until then users disable this by editing
         * `ClawDEASettings.xml` directly.
         */
        var enableWorkspace: Boolean = true,
        /**
         * Filename `WorkspaceDiscovery` looks for while walking up from
         * `project.basePath`. Phase 5 will add a Settings UI; until then
         * users override this by editing `ClawDEASettings.xml` directly.
         */
        var workspaceManifestName: String = ".clawdea-workspace.md",
        var preloadSkillCatalog: Boolean = true,
        // When true, a small always-on values-only behavioral block ("baseline
        // working defaults") is appended to the CLI system prompt. Plugin-owned
        // and model-tunable (text lives in resources/prompts/baseline-defaults.md);
        // CLAUDE.md and installed workflow skills take precedence over it. The
        // block is values-only so it cannot collide with procedural skills.
        var enableBaselineDefaults: Boolean = true,
        // When true, the gateway (CLI fallback path) appends --bare to the
        // claude invocation, skipping hooks, LSP, plugin sync, auto-memory,
        // and CLAUDE.md auto-discovery. On by default; the runtime gate in
        // ClaudeGateway.shouldUseBareMode also requires the active auth
        // provider to be API-key-based ("anthropic" with key, or "bedrock"),
        // so subscription/OAuth users are unaffected even with this true.
        var gatewayBareMode: Boolean = true,
        // Model selector
        var modelCatalogs: MutableMap<String, MutableList<ModelEntry>> = defaultModelCatalogsMap(),
        var selectedModels: MutableMap<String, String> = mutableMapOf(),
        var selectedEfforts: MutableMap<String, String> = mutableMapOf(),
        var roleSelections: MutableMap<String, String> = mutableMapOf(),
        var roleSelectionsMigrated: Boolean = false,

        // OpenAI-compatible provider profiles
        var activeOpenAiCompatibleProfileId: String = "",
        var importedOpenAiProfiles: MutableMap<String, String> = mutableMapOf(),
        var openAiProfileValues: MutableMap<String, String> = mutableMapOf(),
        var openAiEndpointOverrides: MutableMap<String, String> = mutableMapOf(),

        // Profiling
        var profilingSamplingIntervalMs: Int = 10,
        var profilingMaxRecordingMb: Int = 500,
        var profilingMaxDurationSeconds: Int = 900,
        var profilingStackDepth: Int = 128,
        var profilingBackendPreference: String = "auto",
        var profilingMaxRecordings: Int = 20,
        var profilingMaxStorageGb: Int = 5,
        var profilingAutoGitignore: Boolean = true,
        var profilingAutoAnalyze: Boolean = true,
        var profilingTopN: Int = 50,

        // Cost tracking
        var dailyCostUsd: Double = 0.0,
        var dailyCostDate: String = "",
        var dailyBudgetUsd: Double = 0.0,
        /**
         * Per-provider cumulative spend, keyed by providerId. Value is a packed
         * string "sinceDate|monthToDate|allTime|mtdMonth" (mtdMonth = "YYYY-MM" for
         * calendar rollover). Packed as a String so PersistentStateComponent's
         * XmlSerializer handles the Map<String,String> without a custom bean.
         * Parsed via ProviderTotal.parse / format (added in a later task).
         */
        var providerTotals: MutableMap<String, String> = mutableMapOf(),
        /**
         * GLOBAL knowledge-upkeep spend (across all projects), keyed by [KnowledgeBucket] name
         * ("WIKI_CREATE", "WIKI_UPDATE", "WORKSPACE_CREATE", "WORKSPACE_UPDATE") → cumulative USD.
         * App-level so wiki/workspace upkeep reads as one bill regardless of which project ran it.
         */
        var knowledgeUsd: MutableMap<String, Double> = mutableMapOf(),
        /**
         * Persisted cumulative ClawDEA savings estimate (signed; positive = saving). Single
         * global entry under key "global". Packed via SavingsTotal.format / parse. App-level so
         * the all-time verdict persists across projects, like knowledgeUsd.
         */
        var savingsTotal: MutableMap<String, String> = mutableMapOf(),
        /**
         * GLOBAL per-lever estimated savings (Librarian routing, IDE index tools, …), keyed by
         * [LeverId.name] → packed "low|expected|high". App-level so lever breakdowns aggregate
         * across every project and chat tab, like knowledgeUsd.
         */
        var savingsByLever: MutableMap<String, String> = mutableMapOf(),
    )

    private var state = State()

    override fun getState(): State {
        // Clear sensitive fields before serialization — they live in PasswordSafe
        state.apiKey = ""
        state.bedrockBearerToken = ""
        return state
    }

    override fun loadState(state: State) {
        this.state = state
        mergeMissingModelCatalogs(state.modelCatalogs)
        migrateSecretsToPasswordSafe()
        preloadSecrets()
        com.adobe.clawdea.provider.RoleSelectionStore(this).migrateFromLegacyIfNeeded()
    }

    private fun migrateSecretsToPasswordSafe() {
        val migrateApiKey = state.apiKey.takeIf { it.isNotBlank() }
        val migrateBedrockToken = state.bedrockBearerToken.takeIf { it.isNotBlank() }
        if (migrateApiKey == null && migrateBedrockToken == null) return
        if (migrateApiKey != null) {
            secretCache[API_KEY_ATTR.serviceName] = migrateApiKey
            state.apiKey = ""
        }
        if (migrateBedrockToken != null) {
            secretCache[BEDROCK_TOKEN_ATTR.serviceName] = migrateBedrockToken
            state.bedrockBearerToken = ""
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            migrateApiKey?.let { PasswordSafe.instance.setPassword(API_KEY_ATTR, it) }
            migrateBedrockToken?.let { PasswordSafe.instance.setPassword(BEDROCK_TOKEN_ATTR, it) }
        }
    }

    fun getApiKey(): String = getSecret(API_KEY_ATTR)
    fun setApiKey(value: String) = setSecret(API_KEY_ATTR, value)

    fun getBedrockBearerToken(): String = getSecret(BEDROCK_TOKEN_ATTR)
    fun setBedrockBearerToken(value: String) = setSecret(BEDROCK_TOKEN_ATTR, value)

    fun getOpenAIApiKey(): String = getSecret(OPENAI_KEY_ATTR)
    fun setOpenAIApiKey(value: String) = setSecret(OPENAI_KEY_ATTR, value)

    private val secretCache = ConcurrentHashMap<String, String>()

    fun preloadSecrets() {
        ApplicationManager.getApplication().executeOnPooledThread {
            secretCache.getOrPut(API_KEY_ATTR.serviceName) {
                PasswordSafe.instance.getPassword(API_KEY_ATTR).orEmpty()
            }
            secretCache.getOrPut(BEDROCK_TOKEN_ATTR.serviceName) {
                PasswordSafe.instance.getPassword(BEDROCK_TOKEN_ATTR).orEmpty()
            }
            secretCache.getOrPut(OPENAI_KEY_ATTR.serviceName) {
                PasswordSafe.instance.getPassword(OPENAI_KEY_ATTR).orEmpty()
            }
        }
    }

    private fun getSecret(attr: CredentialAttributes): String {
        secretCache[attr.serviceName]?.let { return it }
        if (ApplicationManager.getApplication().isDispatchThread) return ""
        return secretCache.getOrPut(attr.serviceName) {
            PasswordSafe.instance.getPassword(attr).orEmpty()
        }
    }

    private fun setSecret(attr: CredentialAttributes, value: String) {
        val normalized = value.ifBlank { "" }
        secretCache[attr.serviceName] = normalized
        val storeValue = value.ifBlank { null }
        if (ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().executeOnPooledThread {
                PasswordSafe.instance.setPassword(attr, storeValue)
            }
        } else {
            PasswordSafe.instance.setPassword(attr, storeValue)
        }
    }

    /**
     * Returns the model id the user has selected for [workingDirectory] under
     * [providerId] (defaults to the configured provider), validated against
     * that provider's catalog. If the stored id isn't in the catalog (e.g. the
     * user switched providers), returns an empty string so the CLI falls back
     * to its default.
     */
    fun getSelectedModelId(workingDirectory: String, providerId: String = state.apiProvider): String {
        val stored = state.selectedModels[selectedModelKey(workingDirectory, providerId)].orEmpty()
        if (stored.isBlank()) return ""
        val catalog = state.modelCatalogs[providerId] ?: return ""
        return if (catalog.any { it.id == stored }) stored else ""
    }

    fun setSelectedModelId(workingDirectory: String, modelId: String, providerId: String = state.apiProvider) {
        val key = selectedModelKey(workingDirectory, providerId)
        if (modelId.isBlank()) {
            state.selectedModels.remove(key)
        } else {
            state.selectedModels[key] = modelId
        }
    }

    /**
     * Returns the effort level the user has selected for [workingDirectory], or
     * an empty string if unset. The value is the lowercase flag string accepted
     * by `claude --effort` (`low`, `medium`, `high`, `xhigh`, `max`).
     */
    fun getSelectedEffort(workingDirectory: String): String =
        state.selectedEfforts[workingDirectory].orEmpty()

    /**
     * Persists [effort] for [workingDirectory]. A blank value clears the entry,
     * which makes the CLI fall back to its built-in default.
     */
    fun setSelectedEffort(workingDirectory: String, effort: String) {
        if (effort.isBlank()) {
            state.selectedEfforts.remove(workingDirectory)
        } else {
            state.selectedEfforts[workingDirectory] = effort
        }
    }



    /**
     * Resolves the model id to pass to the CLI via `--model` for [providerId]
     * (defaults to the configured provider). Returns the user's explicit pick
     * when present; for subscription, falls back to the first catalog entry
     * when the user picked "Default" so the CLI cannot silently inherit a
     * Bedrock-prefixed model from `~/.claude/settings.json`.
     */
    fun getCliModelId(workingDirectory: String, providerId: String = state.apiProvider): String {
        val explicit = getSelectedModelId(workingDirectory, providerId)
        if (explicit.isNotBlank()) return explicit
        if (providerId != "subscription") return ""
        return state.modelCatalogs["subscription"]?.firstOrNull()?.id.orEmpty()
    }

    private fun selectedModelKey(workingDirectory: String, providerId: String): String =
        "$providerId|$workingDirectory"

    companion object {
        private val API_KEY_ATTR = CredentialAttributes(
            generateServiceName("ClawDEA", "apiKey"),
        )
        private val BEDROCK_TOKEN_ATTR = CredentialAttributes(
            generateServiceName("ClawDEA", "bedrockBearerToken"),
        )
        private val OPENAI_KEY_ATTR = CredentialAttributes(
            generateServiceName("ClawDEA", "openAiApiKey"),
        )

        /**
         * Seed any provider catalogs that were added after this install's `ClawDEASettings.xml`
         * was last written. Persisted state wholesale-replaces the [State] defaults, so a user who
         * first ran an older build has a `modelCatalogs` map missing newer keys (e.g. `openai`,
         * `openai-subscription`) — leaving their model dropdown empty for those providers. Only
         * absent keys are added; a provider the user has already customized is never overwritten.
         */
        internal fun mergeMissingModelCatalogs(catalogs: MutableMap<String, MutableList<ModelEntry>>) {
            for ((provider, seed) in defaultModelCatalogsMap()) {
                catalogs.getOrPut(provider) { seed }
            }
            // One-time correction: an earlier build seeded `openai-subscription` from the API-key
            // catalog (gpt-5-*), but those models return HTTP 400 on a ChatGPT subscription account
            // ("model is not supported when using Codex with a ChatGPT account"). Reset that exact
            // stale seed to the subscription default. A catalog the user has since customized (any
            // list other than the known-bad seed) is left untouched.
            if (catalogs["openai-subscription"] == DEFAULT_OPENAI_CATALOG) {
                catalogs["openai-subscription"] = DEFAULT_OPENAI_SUBSCRIPTION_CATALOG.toMutableList()
            }
        }

        fun getInstance(): ClawDEASettings =
            ApplicationManager.getApplication().getService(ClawDEASettings::class.java)
    }
}
