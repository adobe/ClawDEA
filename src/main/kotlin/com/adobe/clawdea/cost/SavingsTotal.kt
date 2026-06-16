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
 * Persisted cumulative savings. Mirrors [ProviderTotal] but holds a signed [SavingsBand] for
 * both month-to-date and all-time, so the panel can show a range at every scope. Packed as
 * "since|mtdLow|mtdExp|mtdHigh|allLow|allExp|allHigh|mtdMonth".
 */
data class SavingsTotal(
    val sinceDate: String,
    val mtd: SavingsBand,
    val allTime: SavingsBand,
    val mtdMonth: String,
) {
    /** Add a turn's net band. MTD resets when [month] differs; all-time always grows. */
    fun add(net: SavingsBand, today: String, month: String): SavingsTotal {
        val rolledMtd = if (month == mtdMonth) mtd else SavingsBand.ZERO
        val since = sinceDate.ifBlank { today }
        return SavingsTotal(since, rolledMtd + net, allTime + net, month)
    }

    companion object {
        fun empty() = SavingsTotal("", SavingsBand.ZERO, SavingsBand.ZERO, "")

        fun format(t: SavingsTotal): String = listOf(
            t.sinceDate,
            t.mtd.low, t.mtd.expected, t.mtd.high,
            t.allTime.low, t.allTime.expected, t.allTime.high,
            t.mtdMonth,
        ).joinToString("|")

        fun parse(packed: String): SavingsTotal {
            if (packed.isBlank()) return empty()
            val p = packed.split('|')
            fun d(i: Int) = p.getOrNull(i)?.toDoubleOrNull() ?: 0.0
            return SavingsTotal(
                sinceDate = p.getOrNull(0).orEmpty(),
                mtd = SavingsBand(d(1), d(2), d(3)),
                allTime = SavingsBand(d(4), d(5), d(6)),
                mtdMonth = p.getOrNull(7).orEmpty(),
            )
        }
    }
}
