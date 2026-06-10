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
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.time.LocalDate

/**
 * Single source of truth for cost/usage accounting. Project-scoped.
 *
 * Cost semantics: recordTurn treats costUsd as a PER-TURN cost and sums it.
 * Known limitation: session total is per-project, shared across chat tabs; daily
 * always accumulates; resume reseeds the session.
 */
@Service(Service.Level.PROJECT)
class CostTracker(private val project: Project) {

    private val settings get() = ClawDEASettings.getInstance()

    private var sessionUsd = 0.0
    private val perModelUsd = mutableMapOf<String, Double>()

    @Volatile
    private var window: SubscriptionWindow? = null

    @Synchronized
    fun recordTurn(model: String, costUsd: Double, @Suppress("UNUSED_PARAMETER") contextTokens: Int) {
        if (costUsd > 0) {
            sessionUsd += costUsd
            if (model.isNotBlank()) perModelUsd.merge(model, costUsd) { a, b -> a + b }
            addToDaily(costUsd)
        }
        publish()
    }

    @Synchronized
    fun seedSession(baselineUsd: Double) {
        if (baselineUsd > 0) sessionUsd += baselineUsd
        publish()
    }

    @Synchronized
    fun resetSession() {
        sessionUsd = 0.0
        perModelUsd.clear()
        publish()
    }

    fun updateWindow(w: SubscriptionWindow?) {
        window = w
        publish()
    }

    @Synchronized
    fun snapshot(): CostSnapshot {
        val providerId = AuthManager.getInstance().effectiveProviderId()
        val daily = currentDailyUsd()
        val budget = settings.state.dailyBudgetUsd
        val w = window
        val band = if (providerId == "subscription" && w != null) {
            bandForWindow(w)
        } else {
            bandForDollars(daily, budget)
        }
        return CostSnapshot(providerId, sessionUsd, daily, budget, band, perModelUsd.toMap(), w)
    }

    private fun addToDaily(costUsd: Double) {
        val today = LocalDate.now().toString()
        settings.state.dailyCostUsd =
            rolledDaily(settings.state.dailyCostDate, settings.state.dailyCostUsd, today, costUsd)
        settings.state.dailyCostDate = today
    }

    private fun currentDailyUsd(): Double {
        val today = LocalDate.now().toString()
        return if (settings.state.dailyCostDate == today) settings.state.dailyCostUsd else 0.0
    }

    private fun publish() {
        if (project.isDisposed) return
        project.messageBus.syncPublisher(CostSnapshotListener.TOPIC).onCostUpdated(snapshot())
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

        fun bandForWindow(w: SubscriptionWindow): CostBand {
            val pct = maxOf(w.fiveHourPct, w.sevenDayPct)
            return when {
                pct >= 90 -> CostBand.RED
                pct >= 75 -> CostBand.AMBER
                else -> CostBand.GREEN
            }
        }
    }
}
