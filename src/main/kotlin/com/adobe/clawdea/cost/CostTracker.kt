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
package com.adobe.clawdea.cost

import com.adobe.clawdea.auth.AuthManager
import com.adobe.clawdea.cli.CliEvent
import com.adobe.clawdea.cli.CliEventParser
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.time.LocalDate

/**
 * Single source of truth for cost/usage accounting. Project-scoped service, but the
 * session ("chat") total is tracked PER CHAT TAB, keyed by an opaque chatId — two open
 * chats in the same project show independent "$chat" values. Daily spend, the
 * subscription window, and the resolved-default model are shared project-wide.
 *
 * Cost semantics: in a persistent stream-json session the CLI reports `total_cost_usd`
 * as a PER-PROCESS CUMULATIVE session total that grows every turn (verified against
 * `claude` 2.x). recordTurn therefore diffs it against the last reported value to
 * recover this turn's marginal cost, and adds that delta to the chat's running total —
 * summing the raw cumulative would over-count quadratically. A fresh chat starts at 0;
 * resume/restart spawns a new process whose counter resets to ~0, which shows up as a
 * decrease and is handled self-correctingly (see [perTurnDelta]). The flat-rate path
 * (subscription/Bedrock report 0) is unchanged: it is token-priced per turn.
 */
@Service(Service.Level.PROJECT)
class CostTracker(private val project: Project) {

    private val settings get() = ClawDEASettings.getInstance()

    /** Per-chat session accounting. perModelUsd is retained for later per-model stats display. */
    private class ChatCost {
        var sessionUsd: Double = 0.0
        val perModelUsd: MutableMap<String, Double> = mutableMapOf()

        /**
         * Last cumulative `total_cost_usd` reported by the CLI for the current process, used to
         * recover each turn's marginal cost by differencing. Negative means "no cumulative figure
         * seen yet" (fresh chat, or the last turn was flat-rate/token-priced). Reset to -1 whenever
         * the session baseline is reset/reseeded so the next real-dollar turn re-establishes it.
         */
        var lastReportedCumulativeUsd: Double = -1.0
    }

    private val chats = mutableMapOf<String, ChatCost>()
    private fun chat(chatId: String): ChatCost = chats.getOrPut(chatId) { ChatCost() }


    /**
     * The model the "Default" selection resolved to — set only by turns that ran with
     * no explicit model override. Surfaced in the snapshot so the selector can honestly
     * label "Default (<model>)". Never set from an explicit selection or resumed history.
     */
    private var defaultResolvedModel: String? = null

    @Volatile private var usage: SubscriptionUsage = SubscriptionUsage.UNAVAILABLE

    /**
     * Live usage for the OpenAI ChatGPT subscription, from the codex app-server
     * `account/rateLimits/updated` stream (credit balance + rate-limit windows). Kept separate from
     * the Claude [usage] field because the two providers report on independent channels and can both
     * be present. Attaches only to the `openai-subscription` provider.
     */
    @Volatile private var openAiUsage: SubscriptionUsage = SubscriptionUsage.UNAVAILABLE

    /**
     * Record one turn and return its MARGINAL cost (this turn's own spend), which the caller
     * renders in the per-turn footer. See the class-level cost semantics: [costUsd] is the CLI's
     * per-process cumulative total, so the marginal figure is recovered by differencing.
     */
    @Synchronized
    fun recordTurn(
        chatId: String,
        model: String,
        costUsd: Double,
        @Suppress("UNUSED_PARAMETER") contextTokens: Int,
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        cacheReadTokens: Int = 0,
        cacheCreationTokens: Int = 0,
        reasoningTokens: Int = 0,
        /**
         * True when this turn ran with no explicit model override (the "Default"
         * selection, so the CLI chose the model). Only then is [model] evidence of
         * what Default resolves to — an explicit selection tells us nothing about Default.
         */
        ranUnderDefaultSelection: Boolean = false,
        providerId: String = "",
        knowledgeBucket: KnowledgeBucket? = null,
    ): Double {
        if (ranUnderDefaultSelection && model.isNotBlank()) defaultResolvedModel = model
        val c = chat(chatId)
        // `costUsd` is the CLI's PER-PROCESS CUMULATIVE session total, not this turn's cost:
        // recover the marginal delta by differencing against the last reported cumulative. The
        // notional is the flat-rate fallback used when the CLI reports 0 — computed by
        // effectiveTurnCost, which is provider-aware (OpenAI-compatible profile rates incl.
        // reasoning tokens; Claude/Codex model-id pricing).
        val notional = effectiveTurnCost(
            model,
            costUsd,
            inputTokens,
            outputTokens,
            cacheReadTokens,
            cacheCreationTokens,
            reasoningTokens,
            providerId,
        )
        val (effective, newBaseline) = perTurnDelta(costUsd, c.lastReportedCumulativeUsd, notional)
        c.lastReportedCumulativeUsd = newBaseline
        if (effective > 0) {
            c.sessionUsd += effective
            if (model.isNotBlank()) c.perModelUsd.merge(model, effective) { a, b -> a + b }
            if (knowledgeBucket != null) addToKnowledge(knowledgeBucket, effective)
            if (providerId.isNotBlank()) addToProvider(providerId, effective)
            addToDaily(effective)
        }
        publish()
        return effective
    }

    /**
     * Record the cost of the background wiki-author drift subprocess. It runs OUTSIDE any chat
     * (a separate `claude -p` invocation), so its cost feeds daily + the active provider's totals
     * + the WIKI_UPDATE knowledge bucket, but no chat's session total. [stdout] is the subprocess's
     * stream-json output; the trailing `result` event carries cost + token usage.
     */
    fun recordWikiAuthorCost(stdout: String) {
        val result = parseResultLine(stdout) ?: return
        val providerId = AuthManager.getInstance().effectiveProviderId()
        val effective = effectiveTurnCost(
            result.model,
            result.costUsd,
            result.inputTokens,
            result.outputTokens,
            result.cacheReadTokens,
            result.cacheCreationTokens,
            result.reasoningTokens,
            providerId,
        )
        if (effective <= 0) {
            publish()
            return
        }
        addToKnowledge(KnowledgeBucket.WIKI_UPDATE, effective)
        if (providerId.isNotBlank()) addToProvider(providerId, effective)
        addToDaily(effective)
        publish()
    }

    @Synchronized
    fun seedSession(chatId: String, baselineUsd: Double) {
        if (baselineUsd > 0) chat(chatId).sessionUsd += baselineUsd
        publish()
    }

    @Synchronized
    fun resetSession(chatId: String) {
        chats[chatId] = ChatCost()
        // Note: defaultResolvedModel is NOT cleared here — what "Default" resolves to is
        // a provider fact, not session state, and survives across resume/reset within a run.
        publish()
    }

    /**
     * Reset the session view, then seed it from the resumed conversation's transcript.
     * Returns the reconstructed cost (total + last model) so the caller can render a
     * per-turn-style cost footer in the chat, matching what a live turn shows.
     */
    fun seedFromResume(chatId: String, sessionId: String): TranscriptCostReader.ResumeCost {
        resetSession(chatId)
        val base = project.basePath ?: return TranscriptCostReader.ResumeCost(0.0, null)
        val prior = TranscriptCostReader.readResumeCost(
            TranscriptCostReader.sessionTranscriptFile(base, sessionId),
        )
        // Deliberately do NOT set defaultResolvedModel from history: a resumed transcript's
        // model reflects whatever was selected then (maybe an explicit choice, maybe an
        // outdated default), so it isn't reliable evidence of today's Default.
        seedSession(chatId, prior.totalUsd)
        return prior
    }

    /** The model the "Default" selection resolved to (project-wide), or null if not yet observed. */
    @Synchronized
    fun defaultResolvedModel(): String? = defaultResolvedModel

    /** Volatile single-reference; readers snapshot it once. Intentionally not on the instance lock. */
    fun updateUsage(u: SubscriptionUsage) { usage = u; publish() }

    /** Live OpenAI ChatGPT-subscription usage (codex `account/rateLimits/updated`). See [openAiUsage]. */
    fun updateOpenAiUsage(u: SubscriptionUsage) { openAiUsage = u; publish() }

    /**
     * Republish the current state to all listeners. For changes made directly to settings that
     * don't flow through recordTurn (notably the daily budget edited in the Cost Control panel),
     * so every open chip re-snapshots and re-bands immediately instead of waiting for a new chat.
     */
    fun refresh() = publish()

    @Synchronized
    fun resetProvider(providerId: String) {
        synchronized(settings) { settings.state.providerTotals.remove(providerId) }
        publish()
    }

    /**
     * Clear the GLOBAL measured knowledge-upkeep totals (all buckets, all projects).
     * Paired with [SavingsTracker.resetCumulative] behind the Cost Control "Reset
     * all-time" button: that button spans both the estimated savings and the
     * measured upkeep line, so resetting one without the other left a stale upkeep
     * figure (and a stale "Overall") on the card.
     */
    @Synchronized
    fun resetKnowledge() {
        synchronized(settings) { settings.state.knowledgeUsd.clear() }
        publish()
    }

    /** Snapshot for one chat tab: that chat's session/per-model totals + shared daily/window/default. */
    @Synchronized
    fun snapshot(chatId: String): CostSnapshot {
        val providerId = AuthManager.getInstance().effectiveProviderId()
        val daily = currentDailyUsd()
        val budget = settings.state.dailyBudgetUsd
        // Live usage is provider-scoped: Claude subscription reads [usage], the OpenAI ChatGPT
        // subscription reads [openAiUsage]. Everything else has no live gauge.
        val u = liveUsageFor(providerId)
        // A subscription-like provider with live usage → band off the worst utilization (spend or
        // window). Otherwise → band off daily spend vs the user's budget.
        val band = if (u.available) {
            bandForUsage(u)
        } else {
            bandForDollars(daily, budget)
        }
        val c = chats[chatId]
        val providerTotal = settings.state.providerTotals[providerId]?.let { ProviderTotal.parse(it) }
        return CostSnapshot(
            providerId,
            c?.sessionUsd ?: 0.0,
            daily,
            budget,
            band,
            c?.perModelUsd?.toMap() ?: emptyMap(),
            defaultResolvedModel,
            u,
            providerTotal,
            readKnowledge(),
        )
    }

    /**
     * Header data for every provider to render: providers with persisted totals, the active
     * provider, AND subscription whenever live usage is available (so the poller's spend/window
     * data shows even when the user is currently on bedrock). The live subscription [usage]
     * attaches only to the "subscription" block. Chat-scoped body comes from snapshot(chatId).
     */
    @Synchronized
    fun providerBlocks(): List<ProviderBlock> {
        val active = AuthManager.getInstance().effectiveProviderId()
        val pids = LinkedHashSet(usedProviders(settings.state.providerTotals.keys, active))
        if (usage.available) pids.add("subscription")
        if (openAiUsage.available) pids.add("openai-subscription")
        return pids.map { pid ->
            ProviderBlock(
                providerId = pid,
                total = settings.state.providerTotals[pid]?.let { ProviderTotal.parse(it) },
                usage = liveUsageFor(pid),
            )
        }
    }

    /** The live usage gauge for a provider: Claude reads [usage], OpenAI reads [openAiUsage]. */
    private fun liveUsageFor(providerId: String): SubscriptionUsage = when (providerId) {
        "subscription" -> usage
        "openai-subscription" -> openAiUsage
        else -> SubscriptionUsage.UNAVAILABLE
    }

    private fun addToDaily(costUsd: Double) {
        val today = LocalDate.now().toString()
        // Daily total lives on the application-level ClawDEASettings; serialize the
        // read-modify-write on the shared instance so concurrent projects don't lose updates.
        synchronized(settings) {
            settings.state.dailyCostUsd =
                rolledDaily(settings.state.dailyCostDate, settings.state.dailyCostUsd, today, costUsd)
            settings.state.dailyCostDate = today
        }
    }

    private fun addToProvider(providerId: String, costUsd: Double) {
        val today = LocalDate.now().toString()
        val month = today.substring(0, 7)
        synchronized(settings) {
            val cur = ProviderTotal.parse(settings.state.providerTotals[providerId].orEmpty())
            settings.state.providerTotals[providerId] = ProviderTotal.format(cur.add(costUsd, today, month))
        }
    }

    /** Accrue into the GLOBAL knowledge-upkeep total (app settings), keyed by bucket name. */
    private fun addToKnowledge(bucket: KnowledgeBucket, costUsd: Double) {
        synchronized(settings) {
            settings.state.knowledgeUsd.merge(bucket.name, costUsd) { a, b -> a + b }
        }
    }

    /** Read the global knowledge-upkeep totals as a typed map (unknown keys ignored). */
    private fun readKnowledge(): Map<KnowledgeBucket, Double> = synchronized(settings) {
        settings.state.knowledgeUsd.mapNotNull { (k, v) ->
            runCatching { KnowledgeBucket.valueOf(k) }.getOrNull()?.let { it to v }
        }.toMap()
    }

    private fun currentDailyUsd(): Double = synchronized(settings) {
        val today = LocalDate.now().toString()
        if (settings.state.dailyCostDate == today) settings.state.dailyCostUsd else 0.0
    }

    private fun publish() {
        if (project.isDisposed) return
        // Cost state changed somewhere in this project. The snapshot is per-chat now, so
        // this is a payload-free signal: each listener re-queries snapshot(itsChatId).
        // Listeners are invoked synchronously while the instance lock is held; they must
        // update UI quickly and must not call back into CostTracker or block.
        project.messageBus.syncPublisher(CostSnapshotListener.TOPIC).onCostChanged()
    }

    companion object {
        fun getInstance(project: Project): CostTracker =
            project.getService(CostTracker::class.java)

        fun rolledDaily(storedDate: String, storedUsd: Double, today: String, add: Double): Double =
            (if (storedDate == today) storedUsd else 0.0) + add

        fun bandForDollars(daily: Double, budget: Double): CostBand {
            if (budget <= 0.0) return CostBand.NEUTRAL
            val pct = daily / budget
            return when {
                pct >= 0.90 -> CostBand.RED
                pct >= 0.75 -> CostBand.AMBER
                else -> CostBand.GREEN
            }
        }

        /**
         * Band from live subscription usage: the worst utilization across the spend gauge
         * and every rate-limit window (whichever is closest to its limit). NEUTRAL when the
         * usage carries no percentages.
         */
        fun bandForUsage(u: SubscriptionUsage): CostBand {
            val pcts = buildList {
                u.spend?.let { add(it.pct) }
                u.windows.forEach { add(it.pct) }
            }
            val pct = pcts.maxOrNull() ?: return CostBand.NEUTRAL
            return when {
                pct >= 90 -> CostBand.RED
                pct >= 75 -> CostBand.AMBER
                else -> CostBand.GREEN
            }
        }

        /**
         * Effective per-turn cost: the real total_cost_usd when the plan reports one,
         * else a notional cost computed from token usage (subscription/bedrock are
         * flat-rate and report 0). For OpenAI-compatible providers, uses configured
         * per-model rates including reasoning tokens; for Claude/Codex, uses the
         * existing model-id-based pricing table. Keeps the chip moving on every plan.
         */
        fun effectiveTurnCost(
            model: String,
            reportedUsd: Double,
            inputTokens: Int,
            outputTokens: Int,
            cacheReadTokens: Int,
            cacheCreationTokens: Int,
            reasoningTokens: Int,
            providerId: String,
        ): Double {
            if (reportedUsd > 0.0) return reportedUsd

            // OpenAI-compatible provider: use configured rates from the active profile
            if (providerId.startsWith(com.adobe.clawdea.provider.ProviderRegistry.OPENAI_COMPATIBLE_ID)) {
                val profileId = ClawDEASettings.getInstance().state.activeOpenAiCompatibleProfileId
                if (profileId.isNotBlank()) {
                    val profileStore = com.adobe.clawdea.provider.openai.profile.ProfileStore(ClawDEASettings.getInstance())
                    val profile = profileStore.profile(profileId)
                    if (profile != null) {
                        val rates = profile.pricing[model] ?: com.adobe.clawdea.provider.openai.profile.TokenRates()
                        return ModelPricing.costFor(rates, inputTokens, outputTokens, cacheReadTokens, reasoningTokens)
                    }
                }
            }

            // Claude/Codex: use model-id-based pricing (reasoning not applicable, always 0)
            return ModelPricing.costFor(model, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens)
        }

        /** The marginal cost of one turn plus the new baseline to carry forward. See [perTurnDelta]. */
        data class TurnDelta(val delta: Double, val newBaseline: Double)

        /**
         * Recover a single turn's marginal cost from the CLI's PER-PROCESS CUMULATIVE
         * `total_cost_usd`. In a persistent stream-json session that field grows every turn, so the
         * turn's own cost is `reportedCumulative - lastBaseline`. Rules:
         *  - `reportedCumulative <= 0` → flat-rate/token-priced turn: no cumulative to diff. The
         *    caller supplies the notional per-turn figure separately; delta is that [notionalUsd],
         *    and the baseline is left unchanged (`< 0` stays `< 0`) so a later real-dollar turn
         *    re-establishes it from scratch.
         *  - `lastBaseline < 0` (none yet) → first real-dollar turn of this process: delta is the
         *    full reported figure.
         *  - `reportedCumulative < lastBaseline` → the cumulative went BACKWARDS, i.e. a new CLI
         *    process (resume/restart) reset its counter. Treat the reported figure as this turn's
         *    cost and rebase, rather than emitting a negative delta.
         *  - otherwise → normal case: `reportedCumulative - lastBaseline`.
         */
        fun perTurnDelta(reportedCumulative: Double, lastBaseline: Double, notionalUsd: Double): TurnDelta {
            if (reportedCumulative <= 0.0) return TurnDelta(notionalUsd, lastBaseline)
            val delta = when {
                lastBaseline < 0.0 -> reportedCumulative
                reportedCumulative < lastBaseline -> reportedCumulative
                else -> reportedCumulative - lastBaseline
            }
            return TurnDelta(delta, reportedCumulative)
        }

        /** Providers to render: union of persisted-total keys and the active provider (blank ignored). */
        fun usedProviders(stored: Set<String>, active: String): List<String> {
            val set = LinkedHashSet(stored)
            if (active.isNotBlank()) set.add(active)
            return set.toList()
        }

        /** Per-turn cost + token breakdown parsed from a stream-json `result` event. */
        data class ParsedResult(
            val model: String,
            val costUsd: Double,
            val inputTokens: Int,
            val outputTokens: Int,
            val cacheReadTokens: Int,
            val cacheCreationTokens: Int,
            val reasoningTokens: Int,
        )

        /**
         * Scan multi-line stream-json [stdout] for the trailing `result` event and extract its
         * cost + token usage. Returns null if no parseable result line is present. The result
         * event carries no model id, so [ParsedResult.model] falls back to the last assistant
         * line's `message.model` (drives notional pricing when total_cost_usd is 0).
         */
        fun parseResultLine(stdout: String): ParsedResult? {
            val parser = CliEventParser()
            var model = ""
            var result: CliEvent.Result? = null
            stdout.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                when (val ev = parser.parse(line)) {
                    is CliEvent.AssistantMessage -> if (ev.model.isNotBlank()) model = ev.model
                    is CliEvent.Result -> result = ev
                    else -> {}
                }
            }
            val r = result ?: return null
            return ParsedResult(
                model = model,
                costUsd = r.costUsd,
                inputTokens = r.inputTokens,
                outputTokens = r.outputTokens,
                cacheReadTokens = r.cacheReadTokens,
                cacheCreationTokens = r.cacheCreationTokens,
                reasoningTokens = r.reasoningTokens,
            )
        }
    }
}
