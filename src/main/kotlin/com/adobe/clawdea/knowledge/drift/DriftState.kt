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

data class ProbeMiss(
    val query: String,
    val pathTokens: List<String>,
    val hits: Int,
    val contextHash: String,
    val recordedAt: String,
)

data class UserCorrectionRecord(
    val summary: String,
    val contextHash: String,
    val recordedAt: String,
)

/**
 * Persisted drift state. Loaded from `<wikiDir>/.drift-state.json` (default mode)
 * or split between `<wikiDir>/.wiki-state.json` (team-shared) and
 * `<projectBase>/.clawdea/wiki-state.local.json` (per-user) in team mode — see
 * the wiki-team-support spec for the field-split mapping.
 *
 * `lastScanAt` is when the last scan ran (per-user observation). `lastSyncedCommit`
 * is the git SHA the wiki currently describes (team-shared fact). They coexist
 * because they answer different questions.
 *
 * Empty state is the default when the file is missing or malformed. Older files
 * that contain the now-removed `dream*` fields read cleanly: Gson ignores extra
 * JSON fields when no matching property exists.
 */
data class DriftState(
    val lastScanAt: String = "",
    val lastSyncedCommit: String = "",
    val dismissed: List<String> = emptyList(),
    val probeMisses: List<ProbeMiss> = emptyList(),
    val userCorrections: List<UserCorrectionRecord> = emptyList(),
    val suggestions: List<DriftEvent.WikiSuggestion> = emptyList(),
) {
    companion object {
        const val MAX_PROBE_MISSES = 200
        const val MAX_USER_CORRECTIONS = 100
    }
}
