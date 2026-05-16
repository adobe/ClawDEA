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
package com.adobe.clawdea.knowledge.wiki

import com.adobe.clawdea.knowledge.drift.DriftEvent
import com.adobe.clawdea.knowledge.drift.DriftStateStore
import com.adobe.clawdea.knowledge.drift.SuggestionKind
import java.nio.file.Path
import java.time.Instant

/**
 * Validates a librarian-recorded wiki suggestion and persists it into
 * `.claude/wiki/.drift-state.json`'s `suggestions` array. Idempotent on
 * signature: re-recording the same gap updates the existing entry's
 * title/rationale/recordedAt rather than appending a duplicate.
 */
class WikiSuggestionWriter(private val claudeDir: Path) {

    sealed class Result {
        data class Recorded(val signature: String, val isNew: Boolean) : Result()
        data class Dismissed(val signature: String) : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun record(
        kind: String,
        title: String,
        rationale: String,
        targetFilesCsv: String,
        sourcePage: String?,
        recordedAt: Instant = Instant.now(),
    ): Result {
        val kindEnum = parseKind(kind)
            ?: return Result.Invalid("kind must be missingConcept, staleConcept, or incompleteConcept (got '$kind')")
        if (title.length !in 3..120)
            return Result.Invalid("title length must be 3..120 (got ${title.length})")
        if (rationale.length !in 10..800)
            return Result.Invalid("rationale length must be 10..800 (got ${rationale.length})")

        val targetFiles = targetFilesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (targetFiles.isEmpty())
            return Result.Invalid("target_files must list at least one wiki path")
        targetFiles.firstOrNull { !isSafeWikiPath(it) }?.let { bad ->
            return Result.Invalid("target_files entry is not a safe wiki path: $bad")
        }
        val safeSourcePage = sourcePage?.trim()?.takeIf { it.isNotEmpty() }
        if (safeSourcePage != null && !isSafeWikiPath(safeSourcePage)) {
            return Result.Invalid("source_page is not a safe wiki path: $safeSourcePage")
        }

        val event = DriftEvent.WikiSuggestion(
            kind = kindEnum,
            title = title,
            rationale = rationale,
            targetFiles = targetFiles,
            sourcePage = safeSourcePage,
            recordedAt = recordedAt.toString(),
        )

        val state = DriftStateStore.read(claudeDir)
        if (event.signature in state.dismissed) return Result.Dismissed(event.signature)

        val existing = state.suggestions.indexOfFirst { it.signature == event.signature }
        val newSuggestions = if (existing >= 0) {
            state.suggestions.toMutableList().also { it[existing] = event }
        } else {
            state.suggestions + event
        }
        DriftStateStore.write(claudeDir, state.copy(suggestions = newSuggestions))
        return Result.Recorded(event.signature, isNew = existing < 0)
    }

    private fun parseKind(raw: String): SuggestionKind? = try {
        SuggestionKind.valueOf(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun isSafeWikiPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (!path.startsWith(".claude/wiki/")) return false
        if (!path.endsWith(".md")) return false
        val segments = path.split('/')
        return segments.none { it == ".." || it.isBlank() || it.contains('\\') }
    }
}
