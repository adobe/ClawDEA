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

import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.time.LocalDate

/**
 * Owns the ClawDEA savings estimate. Project-scoped, session ("chat") band tracked per chatId
 * (mirrors CostTracker). Cumulative MTD/all-time and per-lever totals are GLOBAL (app settings),
 * like knowledgeUsd. Fed live from EventStreamHandler and reseeded from a transcript on resume.
 * Publishes the existing CostSnapshotListener topic so the panel re-snapshots.
 */
@Service(Service.Level.PROJECT)
class SavingsTracker(private val project: Project) {

    private val settings get() = ClawDEASettings.getInstance()

    private class ChatSavings {
        var band: SavingsBand = SavingsBand.ZERO
        var lastComponents: List<SavingsComponent> = emptyList()
        var turnCount: Int = 0
    }

    private val chats = mutableMapOf<String, ChatSavings>()
    private fun chat(id: String) = chats.getOrPut(id) { ChatSavings() }

    @Synchronized
    fun recordTurn(chatId: String, obs: TurnObservation) {
        val comps = SavingsEstimator.components(obs)
        val net = comps.fold(SavingsBand.ZERO) { acc, c -> acc + c.band }
        val c = chat(chatId)
        c.band += net
        c.lastComponents = comps
        c.turnCount += 1
        accrueCumulative(net)
        accrueLevers(comps)
        publish()
    }

    @Synchronized
    fun resetSession(chatId: String) {
        chats[chatId] = ChatSavings()
        publish()
    }

    /** Replace the session with a fresh chat seeded from a resumed transcript's reconstructed band. */
    @Synchronized
    fun seedFromResume(chatId: String, reconstructed: SavingsBand, turns: Int) {
        val c = ChatSavings()
        c.band = reconstructed
        c.turnCount = turns
        chats[chatId] = c
        publish()
    }

    @Synchronized
    fun resetCumulative() {
        synchronized(settings) {
            settings.state.savingsTotal.remove(GLOBAL_KEY)
            settings.state.savingsByLever.clear()
        }
        publish()
    }

    @Synchronized
    fun snapshot(chatId: String): SavingsSnapshot {
        val c = chats[chatId]
        return SavingsSnapshot(
            sessionBand = c?.band ?: SavingsBand.ZERO,
            cumulative = readCumulative(),
            leverBands = readLeverBands(),
            components = c?.lastComponents ?: emptyList(),
            turnCount = c?.turnCount ?: 0,
        )
    }

    private fun accrueCumulative(net: SavingsBand) {
        val today = LocalDate.now().toString()
        val month = today.substring(0, 7)
        synchronized(settings) {
            val cur = SavingsTotal.parse(settings.state.savingsTotal[GLOBAL_KEY].orEmpty())
            settings.state.savingsTotal[GLOBAL_KEY] = SavingsTotal.format(cur.add(net, today, month))
        }
    }

    private fun accrueLevers(components: List<SavingsComponent>) {
        synchronized(settings) {
            for (c in components) {
                if (c.measured) continue
                LeverBandStore.accrue(settings.state.savingsByLever, c.leverId, c.band)
            }
        }
    }

    private fun readCumulative(): SavingsTotal = synchronized(settings) {
        SavingsTotal.parse(settings.state.savingsTotal[GLOBAL_KEY].orEmpty())
    }

    private fun readLeverBands(): Map<LeverId, SavingsBand> = synchronized(settings) {
        LeverBandStore.readAll(settings.state.savingsByLever)
    }

    private fun publish() {
        if (project.isDisposed) return
        project.messageBus.syncPublisher(CostSnapshotListener.TOPIC).onCostChanged()
    }

    companion object {
        private const val GLOBAL_KEY = "global"
        fun getInstance(project: Project): SavingsTracker = project.getService(SavingsTracker::class.java)
    }
}
