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

/**
 * Per-kind icon and short label mappings for [DriftEvent]s, used by every
 * drift-surfacing UI (chat banner, auto-apply notifications, the
 * wiki-author digest, and the legacy `/refresh-wiki` prompt) so that all
 * surfaces share a consistent visual vocabulary.
 *
 * The `when` branches are exhaustive over the sealed [DriftEvent] hierarchy
 * deliberately — no `else`. Adding a new subtype must force a compile error
 * here so we never silently render an unlabeled event.
 */
object DriftEventIcon {
    fun iconFor(event: DriftEvent): String = when (event) {
        is DriftEvent.CodeRename -> "🔗"
        is DriftEvent.ManifestStale -> "📋"
        is DriftEvent.CommitDrift -> "↻"
        is DriftEvent.WikiSuggestion -> "✍"
    }

    fun labelFor(event: DriftEvent): String = when (event) {
        is DriftEvent.CodeRename -> "stale link"
        is DriftEvent.ManifestStale -> "stale manifest"
        is DriftEvent.CommitDrift -> "code changed"
        is DriftEvent.WikiSuggestion -> "suggested update"
    }
}
