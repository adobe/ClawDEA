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

/** Parsed per-provider cumulative spend. Persisted packed as "since|mtd|allTime|mtdMonth". */
data class ProviderTotal(
    val sinceDate: String,
    val monthToDate: Double,
    val allTime: Double,
    val mtdMonth: String,
) {
    /** Add a turn cost; resets MTD when [month] differs from the stored month. All-time always grows. */
    fun add(costUsd: Double, today: String, month: String): ProviderTotal {
        val rolledMtd = if (month == mtdMonth) monthToDate else 0.0
        val since = sinceDate.ifBlank { today }
        return ProviderTotal(since, rolledMtd + costUsd, allTime + costUsd, month)
    }

    companion object {
        fun parse(packed: String): ProviderTotal {
            val p = packed.split('|')
            return ProviderTotal(
                sinceDate = p.getOrNull(0).orEmpty(),
                monthToDate = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
                allTime = p.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
                mtdMonth = p.getOrNull(3).orEmpty(),
            )
        }
        fun format(t: ProviderTotal): String =
            "${t.sinceDate}|${t.monthToDate}|${t.allTime}|${t.mtdMonth}"
    }
}
