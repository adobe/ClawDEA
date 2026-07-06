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

/**
 * Packed persistence for per-lever cumulative savings bands in [ClawDEASettings.state.savingsByLever].
 * App-level (all projects), like [KnowledgeBucket] upkeep totals.
 */
object LeverBandStore {

    fun format(b: SavingsBand): String = listOf(b.low, b.expected, b.high).joinToString("|")

    fun parse(packed: String): SavingsBand {
        if (packed.isBlank()) return SavingsBand.ZERO
        val p = packed.split('|')
        fun d(i: Int) = p.getOrNull(i)?.toDoubleOrNull() ?: 0.0
        return SavingsBand(d(0), d(1), d(2))
    }

    fun readAll(stored: Map<String, String>): Map<LeverId, SavingsBand> =
        LeverId.entries.associateWith { id -> parse(stored[id.name].orEmpty()) }

    fun accrue(stored: MutableMap<String, String>, leverId: LeverId, delta: SavingsBand) {
        val cur = parse(stored[leverId.name].orEmpty())
        stored[leverId.name] = format(cur + delta)
    }
}
