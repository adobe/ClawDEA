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

/** Soft-warning band derived from spend vs budget (or subscription window). */
enum class CostBand { NEUTRAL, GREEN, AMBER, RED }

/** Subscription rate-limit windows from the Anthropic oauth/usage endpoint. */
data class SubscriptionWindow(
    val fiveHourPct: Int,
    val sevenDayPct: Int,
    val resetEpochMs: Long,
)

/** Immutable view published to the UI after every cost update. */
data class CostSnapshot(
    val providerId: String,
    val sessionUsd: Double,
    val dailyUsd: Double,
    val dailyBudgetUsd: Double,
    val band: CostBand,
    val perModelUsd: Map<String, Double>,
    val window: SubscriptionWindow? = null,
)
