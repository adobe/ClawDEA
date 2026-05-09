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

import com.adobe.clawdea.knowledge.primer.PrimerSource
import com.intellij.openapi.project.Project
import java.nio.file.Files

class NotesSource : PrimerSource {
    override val id = "notes"

    override fun load(project: Project): String? {
        val current = NotesPaths.currentMd(project) ?: return null
        if (!Files.exists(current)) return null
        val text = runCatching { Files.readString(current) }.getOrNull() ?: return null
        return renderBody(text)
    }

    companion object {
        private const val MAX_CHARS = 16 * 1024
        private const val DIRECTIVE =
            "Personal notes from this user. Private context — preferences, in-flight work, hypotheses. " +
                "Treat as authoritative for what's currently in scope."
        private const val TRUNCATION_MARKER = "...(older entries truncated)..."

        internal fun renderBody(currentMdContent: String?): String? {
            if (currentMdContent == null) return null
            val (kept, truncated) = if (currentMdContent.length > MAX_CHARS) {
                currentMdContent.substring(0, MAX_CHARS) to true
            } else {
                currentMdContent to false
            }
            return buildString {
                append(DIRECTIVE)
                append("\n\n")
                append(kept)
                if (truncated) {
                    if (!kept.endsWith("\n")) append("\n")
                    append(TRUNCATION_MARKER)
                    append("\n")
                }
            }
        }
    }
}
