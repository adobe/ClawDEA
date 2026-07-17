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
package com.adobe.clawdea.knowledge.drift

import com.adobe.clawdea.knowledge.wiki.WikiLocator
import com.adobe.clawdea.knowledge.workspace.WorkspaceDiscovery
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class DriftDetectionService(private val project: Project, private val cs: CoroutineScope) {

    /**
     * Fire-and-forget rescan on the service scope. Used by callers that mutate
     * drift state outside the periodic loop — notably `record_wiki_suggestion`,
     * which appends to `suggestions` and needs the wiki-author to run promptly
     * rather than waiting up to a full [DriftPeriodicScanner.INTERVAL_MS] tick.
     * [rescan] serializes on [rescanMutex], so a concurrent periodic tick can
     * never overlap this one. Wrapped in [NonCancellable] so a scope shutdown
     * can't abort a rescan that already launched the wiki-author subprocess.
     */
    fun requestRescan() {
        cs.launch(Dispatchers.IO) {
            try {
                withContext(NonCancellable) { rescan() }
            } catch (e: Throwable) {
                LOG.warn("requestRescan failed: ${e.message}")
            }
        }
    }

    // Serializes the heavy rescan body (which suspends for the full duration of the
    // wiki-author subprocess — up to ~5 minutes). A coroutine [Mutex] rather than a
    // JVM monitor so it can be held across suspension points without pinning a
    // thread; the EDT-facing accessors below are deliberately lock-free so a long
    // rescan can never freeze the EDT.
    private val rescanMutex = Mutex()

    // Read by ChatPanel from the EDT. Kept lock-free (volatile + copy-on-write) so
    // EDT reads never contend with a long-running rescan holding [rescanMutex].
    @Volatile private var lastEvents: List<DriftEvent> = emptyList()
    @Volatile private var lastApplied: List<DriftEvent> = emptyList()
    private val listeners = CopyOnWriteArrayList<(events: List<DriftEvent>, applied: List<DriftEvent>) -> Unit>()

    suspend fun rescan(): Pair<List<DriftEvent>, List<DriftEvent>> = rescanMutex.withLock {
        val basePath = project.basePath
        if (basePath == null) {
            lastEvents = emptyList()
            lastApplied = emptyList()
            notifyListeners()
            return emptyList<DriftEvent>() to emptyList()
        }
        // Skip while a git operation is in progress (rebase, merge, cherry-pick,
        // revert). Writing `.wiki-state.json` mid-rebase dirties the working tree
        // and breaks `git rebase --continue` with "You have unstaged changes".
        if (isRepoBusy(project)) {
            return lastEvents to lastApplied
        }
        val projectBase = Paths.get(basePath)
        val wikiDir = WikiLocator.getInstance(project).wikiDir()
        val state = DriftStateStore.read(wikiDir = wikiDir, projectBase = projectBase)
        val settings = ClawDEASettings.getInstance().state
        val now = Instant.now()
        val raw = collectRaw(
            project = project,
            projectRoot = projectBase,
            wikiDir = wikiDir,
            beforeState = state,
            settingsState = settings,
            now = now,
        )
        val filtered = filterDismissed(raw, state)
        val invoker = buildInvoker(basePath, wikiDir)
        val (remaining, applied) =
            applyAndDismiss(filtered, settings.autoUpdateWiki, state, DriftAutoApplier.todayIso(), invoker)
        val headSha = currentHeadSha(project) ?: ""
        // Only advance lastSyncedCommit on first-run baseline or when wiki content
        // was actually authored. Advancing on every tick rewrites the team-shared
        // `.wiki-state.json` and creates a dirty file after every commit, which is
        // what makes `git rebase` (and `git stash pop`) refuse to start.
        val advanceSync = shouldAdvanceSync(state, applied)
        val appliedSignatures = applied.events.map { it.signature }.toSet()
        // Merge the rescan's deltas into the LATEST on-disk state rather than
        // writing back the snapshot read at the start. The wiki-librarian (running
        // in the chat session) appends to `suggestions` via WikiSuggestionWriter
        // while this rescan is in flight; writing the start snapshot would clobber
        // those freshly recorded suggestions (lost update) so they'd never reach
        // the wiki-author and no document update would ever happen. Re-reading here
        // preserves concurrently recorded suggestions and drops the ones we just
        // authored.
        DriftStateStore.update(wikiDir = wikiDir, projectBase = projectBase) { latest ->
            latest.copy(
                lastScanAt = now.toString(),
                dismissed = (latest.dismissed + appliedSignatures).distinct(),
                suggestions = latest.suggestions.filterNot { it.signature in appliedSignatures },
                lastSyncedCommit = if (advanceSync && headSha.isNotBlank()) headSha else latest.lastSyncedCommit,
            )
        }
        lastEvents = remaining
        lastApplied = applied.events
        notifyListeners()
        return remaining to applied.events
    }

    fun current(): List<DriftEvent> = lastEvents
    fun lastAppliedEvents(): List<DriftEvent> = lastApplied

    fun recordProbeMiss(query: String, pathTokens: List<String>, hits: Int, contextHash: String) {
        val basePath = project.basePath ?: return
        val projectBase = Paths.get(basePath)
        val wikiDir = WikiLocator.getInstance(project).wikiDir()
        val miss = ProbeMiss(query, pathTokens, hits, contextHash, Instant.now().toString())
        DriftStateStore.update(wikiDir = wikiDir, projectBase = projectBase) { state ->
            val updated = state.probeMisses + miss
            state.copy(probeMisses = updated.takeLast(DriftState.MAX_PROBE_MISSES))
        }
    }

    fun recordUserCorrection(correctionSummary: String, contextHash: String) {
        val basePath = project.basePath ?: return
        val projectBase = Paths.get(basePath)
        val wikiDir = WikiLocator.getInstance(project).wikiDir()
        val correction = UserCorrectionRecord(correctionSummary.take(500), contextHash, Instant.now().toString())
        DriftStateStore.update(wikiDir = wikiDir, projectBase = projectBase) { state ->
            val updated = state.userCorrections + correction
            state.copy(userCorrections = updated.takeLast(DriftState.MAX_USER_CORRECTIONS))
        }
    }

    suspend fun dismiss(signature: String) {
        val basePath = project.basePath ?: return
        // Same in-progress-git guard as `rescan` — dismiss rewrites the
        // team-shared `.wiki-state.json` (it strips the signature from
        // `suggestions`), so it has the same dirty-tree hazard.
        if (isRepoBusy(project)) return
        val projectBase = Paths.get(basePath)
        val wikiDir = WikiLocator.getInstance(project).wikiDir()
        DriftStateStore.update(wikiDir = wikiDir, projectBase = projectBase) { state ->
            state.copy(
                dismissed = state.dismissed + signature,
                suggestions = state.suggestions.filterNot { it.signature == signature },
            )
        }
        rescan()
    }

    fun addListener(l: (events: List<DriftEvent>, applied: List<DriftEvent>) -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }

    private fun notifyListeners() {
        val events = lastEvents
        val applied = lastApplied
        for (l in listeners) {
            try { l(events, applied) } catch (e: Throwable) { LOG.warn("listener threw: ${e.message}") }
        }
    }

    private fun currentHeadSha(project: Project): String? {
        val repo = git4idea.repo.GitRepositoryManager.getInstance(project)
            .repositories.firstOrNull() ?: return null
        return repo.currentRevision
    }

    private fun isRepoBusy(project: Project): Boolean {
        val repo = git4idea.repo.GitRepositoryManager.getInstance(project)
            .repositories.firstOrNull() ?: return false
        return repo.state != com.intellij.dvcs.repo.Repository.State.NORMAL
    }

    /**
     * The ROUTING SEAM. Resolves the WIKI role's [com.adobe.clawdea.provider.AgentSelection] and
     * branches on the selection's backend kind (capability-tiered per design §5.2):
     *  - CLAUDE_CLI → the EXISTING [DefaultWikiAuthorInvoker] (byte-identical `claude -p --agents`
     *    path), with its `--model` sourced from the WIKI selection and its subprocess env applied
     *    for the WIKI selection's provider.
     *  - OPENAI_COMPATIBLE_HTTP → an [AgenticWikiAuthorInvoker] running the digest through the
     *    agentic tool loop (find_symbol/Edit/Write via the MCP + host catalog), NO `--agents`.
     *  - CODEX_APP_SERVER → [CodexUnsupportedWikiAuthorInvoker] (documented follow-up).
     */
    private fun buildInvoker(basePath: String, wikiDir: Path): WikiAuthorInvoker {
        val settings = ClawDEASettings.getInstance()
        val wikiSelection = com.adobe.clawdea.provider.RoleSelectionStore(settings).get(com.adobe.clawdea.provider.AgentRole.WIKI)
        val kind = com.adobe.clawdea.provider.ProviderRegistry.require(wikiSelection.providerId).backendKind
        return chooseWikiInvoker(
            kind = kind,
            claude = {
                val cliPath = com.adobe.clawdea.cli.resolveClaudeCliPath(settings.state.cliPath)
                val mcpPort = com.adobe.clawdea.mcp.McpServer.getInstance(project).port
                // Model now comes from the WIKI selection (post-migration this equals today's value,
                // so the Claude path is unchanged by default). Everything else — --agents,
                // disallowedTools, digest, timeouts, cost attribution — stays byte-identical.
                DefaultWikiAuthorInvoker(
                    claudeCliPath = cliPath,
                    projectRoot = Paths.get(basePath),
                    mcpPort = mcpPort,
                    modelId = wikiSelection.modelId,
                    wikiDir = wikiDir,
                    selection = wikiSelection,
                    // Attribute the subprocess's cost to the WIKI_UPDATE knowledge bucket + daily/provider
                    // totals. Runs outside any chat, so it never touches a chat's session total.
                    onStdout = { stdout ->
                        project.getService(com.adobe.clawdea.cost.CostTracker::class.java)
                            .recordWikiAuthorCost(stdout)
                    },
                )
            },
            agentic = { buildAgenticWikiInvoker(wikiSelection, wikiDir) },
            codexUnsupported = { CodexUnsupportedWikiAuthorInvoker },
        )
    }

    /**
     * Build the openai-compatible agentic wiki-author invoker: resolve the profile + capability,
     * construct a headless [LoopBackedWikiSession] over the SAME MCP/host tool catalog the chat
     * uses. The capability guard lives inside [AgenticWikiAuthorInvoker.invoke] so a completion-only
     * model produces a clear error rather than a silent no-op author.
     */
    private fun buildAgenticWikiInvoker(
        wikiSelection: com.adobe.clawdea.provider.AgentSelection,
        wikiDir: Path,
    ): WikiAuthorInvoker {
        val settings = ClawDEASettings.getInstance()
        val profileStore = com.adobe.clawdea.provider.openai.profile.ProfileStore(settings)
        val profileId = wikiSelection.profileId ?: ""
        val profile = profileStore.resolve(profileId, System.getenv())
        // Resolve capability so the guard can refuse tool-less models.
        val capability = if (profile != null) {
            com.adobe.clawdea.provider.openai.catalog.ModelCapabilityResolver.resolve(
                modelId = wikiSelection.modelId,
                endpointCapability = null,
                profileRules = profile.profile.modelRules,
                userOverride = null,
            )
        } else {
            com.adobe.clawdea.provider.openai.catalog.ModelCapability.UNKNOWN
        }

        val mcpDefs = com.adobe.clawdea.mcp.McpServer.getInstance(project).toolDefinitions()
        val approvalGate = com.adobe.clawdea.provider.openai.tools.SharedToolApprovalGate(
            toolApprovalMode = { settings.state.toolApprovalMode },
            policy = { null },
            route = { toolName, inputJson, toolUseId ->
                com.adobe.clawdea.chat.permission.PermissionRouterRegistry(project).route(toolName, inputJson, toolUseId)
            },
            promptTimeoutMs = 300_000,
        )

        val session = AgenticWikiSession { digest ->
            val credential = com.adobe.clawdea.provider.openai.auth.ProfileCredentialStore().get(profileId)
            if (profile == null) {
                Result.failure(RuntimeException("OpenAI-compatible profile '$profileId' not configured"))
            } else if (credential.isBlank()) {
                Result.failure(RuntimeException("Credential not configured for profile '$profileId'"))
            } else {
                val client = com.adobe.clawdea.cli.backend.HttpAgentClient(
                    com.adobe.clawdea.provider.openai.client.OpenAiCompatibleClient(), profile, credential,
                )
                val executor = com.adobe.clawdea.cli.backend.defaultExecutor(
                    project, mcpDefs, approvalGate, { settings.state.autoAcceptEdits },
                )
                LoopBackedWikiSession(
                    client = client,
                    executor = executor,
                    tools = com.adobe.clawdea.cli.backend.agentToolDefinitions(mcpDefs),
                    modelId = wikiSelection.modelId,
                    systemPrompt = wikiAuthorSystemPrompt(),
                    streaming = profile.profile.streaming,
                ).run(digest)
            }
        }
        return AgenticWikiAuthorInvoker(wikiSelection, wikiDir, capability, session)
    }

    /**
     * The wiki-author agent's prompt body (from `/agents/wiki-author.md`) reused as the system
     * prompt for the non-Claude path. On the Claude path this is injected via `--agents`; here it
     * seeds the single agentic session. Degrades to null on any packaging error (the digest itself
     * still carries the instructions).
     */
    private fun wikiAuthorSystemPrompt(): String? = try {
        com.adobe.clawdea.knowledge.wiki.WikiAgentsArg.authorPromptBody()
    } catch (e: Throwable) {
        LOG.warn("wiki-author (agentic) could not load author prompt body: ${e.message}")
        null
    }

    companion object {
        private val LOG = Logger.getInstance(DriftDetectionService::class.java)

        /**
         * Pure routing decision for the WIKI role: pick the invoker factory for [kind]. Extracted so
         * the capability-tiered branch is unit-testable without IntelliJ services. The factories are
         * evaluated lazily so only the chosen branch runs (a Claude selection never touches the
         * agentic/codex builders and vice-versa).
         */
        fun chooseWikiInvoker(
            kind: com.adobe.clawdea.provider.BackendKind,
            claude: () -> WikiAuthorInvoker,
            agentic: () -> WikiAuthorInvoker,
            codexUnsupported: () -> WikiAuthorInvoker,
        ): WikiAuthorInvoker = when (kind) {
            com.adobe.clawdea.provider.BackendKind.CLAUDE_CLI -> claude()
            com.adobe.clawdea.provider.BackendKind.OPENAI_COMPATIBLE_HTTP -> agentic()
            com.adobe.clawdea.provider.BackendKind.CODEX_APP_SERVER -> codexUnsupported()
        }

        data class ApplyResult(val events: List<DriftEvent>, val newState: DriftState)

        internal fun bumpSyncedCommit(state: DriftState, headSha: String): DriftState {
            if (headSha.isBlank()) return state
            return state.copy(lastSyncedCommit = headSha)
        }

        /**
         * `lastSyncedCommit` is "the commit the wiki currently describes". Advance
         * it only when one of these holds:
         *  - First run baseline: the field is blank — adopt current HEAD so the
         *    next scan has a reference point for the `lastSyncedCommit..HEAD`
         *    range. Without this the detector would report every commit forever.
         *  - Wiki content actually changed in this scan: at least one event was
         *    applied (deterministic auto-apply or wiki-author edit).
         *
         * Routine ticks where nothing was authored leave the SHA where it is,
         * which means `.wiki-state.json` only changes when the wiki content
         * itself changes. That keeps the team-shared file out of working-tree
         * diffs that would block `git rebase` / `git stash pop`.
         */
        internal fun shouldAdvanceSync(beforeState: DriftState, applied: ApplyResult): Boolean {
            if (beforeState.lastSyncedCommit.isBlank()) return true
            return applied.events.isNotEmpty()
        }

        internal fun collectRaw(
            project: Project,
            projectRoot: Path,
            wikiDir: Path,
            beforeState: DriftState,
            settingsState: ClawDEASettings.State,
            now: Instant,
        ): List<DriftEvent> {
            val out = mutableListOf<DriftEvent>()
            out += CodeRenameDetector.detect(
                wikiDir = wikiDir,
                sourceRoots = listOf(
                    projectRoot.resolve("src/main/kotlin"),
                    projectRoot.resolve("src/main/java"),
                ),
            )
            val manifestPath = WorkspaceDiscovery.discover(projectRoot)
            if (manifestPath != null) {
                out += ManifestStaleDetector.detect(manifestPath)
            }
            if (settingsState.enableWikiLibrarian) {
                out += CommitWikiDriftDetector.detect(
                    project = project,
                    wikiDir = wikiDir,
                    lastSyncedCommit = beforeState.lastSyncedCommit,
                    now = now,
                )
                out += OrphanCodeDetector.detect(
                    wikiDir = wikiDir,
                    sourceRoots = listOf(
                        projectRoot.resolve("src/main/kotlin"),
                        projectRoot.resolve("src/main/java"),
                    ),
                )
            }
            out += beforeState.suggestions
            return out
        }

        internal fun filterDismissed(raw: List<DriftEvent>, state: DriftState): List<DriftEvent> {
            val dismissed = state.dismissed.toSet()
            return raw.filterNot { it.signature in dismissed }
        }

        suspend fun applyAndDismiss(
            events: List<DriftEvent>,
            autoUpdateEnabled: Boolean,
            beforeState: DriftState,
            today: String,
            wikiAuthorInvoker: WikiAuthorInvoker,
        ): Pair<List<DriftEvent>, ApplyResult> {
            LOG.info("applyAndDismiss: events=${events.size} autoUpdate=$autoUpdateEnabled kinds=${events.groupingBy { it::class.simpleName }.eachCount()}")
            if (!autoUpdateEnabled || events.isEmpty()) {
                return events to ApplyResult(emptyList(), beforeState)
            }
            // Step 1: deterministic auto-apply (CodeRename + ManifestStale) — fast path.
            val deterministicApplied = DriftAutoApplier.apply(events, today)
            val afterDeterministic = events - deterministicApplied.toSet()

            // Step 2: route remainder through wiki-author.
            val needsAuthor = afterDeterministic.filter {
                it is DriftEvent.CommitDrift || it is DriftEvent.WikiSuggestion ||
                    it is DriftEvent.OrphanSubsystem ||
                    (it is DriftEvent.CodeRename && it.suggestedReplacement == null) ||
                    (it is DriftEvent.ManifestStale)  // edge case: deterministic apply failed
            }
            val authoredAcked = if (needsAuthor.isNotEmpty()) {
                wikiAuthorInvoker.invoke(needsAuthor).actedOnSignatures
            } else emptySet()
            val authoredApplied = needsAuthor.filter { it.signature in authoredAcked }

            val applied = deterministicApplied + authoredApplied
            // When autoUpdateEnabled, hide wiki-author events from the banner:
            // they are being handled by the subagent, which will surface its own UI
            // (edit-review dialog, chat panel render) as needed. Don't re-surface
            // them in the "remaining" list that feeds the drift banner.
            val remaining = if (autoUpdateEnabled) {
                events - applied.toSet() - needsAuthor.toSet()
            } else {
                events - applied.toSet()
            }
            val newState = beforeState.copy(
                dismissed = beforeState.dismissed + applied.map { it.signature },
            )
            return remaining to ApplyResult(applied, newState)
        }
    }
}
