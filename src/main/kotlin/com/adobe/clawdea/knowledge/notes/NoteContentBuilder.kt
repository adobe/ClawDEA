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
package com.adobe.clawdea.knowledge.notes

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object NoteContentBuilder {
    private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun prepend(
        existingContent: String?,
        body: String,
        now: LocalDateTime,
    ): String {
        val heading = "## " + FORMATTER.format(now)
        val trimmedBody = body.trimEnd()
        val newEntry = "$heading\n\n$trimmedBody\n\n"
        val existing = existingContent?.takeIf { it.isNotBlank() }
        return if (existing != null) newEntry + existing else newEntry
    }
}
