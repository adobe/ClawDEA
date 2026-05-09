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
package com.adobe.clawdea.commands.handlers

import com.adobe.clawdea.commands.CommandCategory
import com.adobe.clawdea.commands.CommandInfo
import com.adobe.clawdea.knowledge.notes.NotesPaths
import com.adobe.clawdea.knowledge.notes.PromoteToWikiPromptBuilder
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.project.Project
import java.nio.file.Files

object PromoteToWikiHandler {
    fun create(project: Project): BridgeExpandingHandler =
        BridgeExpandingHandler(
            CommandInfo("/promote-to-wiki", "Publish a private note into the team wiki via diff review", CommandCategory.BRIDGE),
        ) { args ->
            val arg = args.trim()
            if (arg.isEmpty()) return@BridgeExpandingHandler "usage: `/promote-to-wiki <file>` (path is relative to the personal notes directory; e.g. `CURRENT.md`)"

            project.basePath
                ?: return@BridgeExpandingHandler "(no project basePath — cannot resolve notes directory)"

            val sourcePath = NotesPaths.resolveNoteFile(project, arg)
                ?: return@BridgeExpandingHandler "Invalid note path: `$arg`. Must be a file inside the personal notes directory (no `..`, no absolute paths)."

            if (!Files.exists(sourcePath)) {
                return@BridgeExpandingHandler "Note not found: `$sourcePath`. Use `/note` to create one, or pass an existing file under the notes directory."
            }
            if (Files.isDirectory(sourcePath)) {
                return@BridgeExpandingHandler "Note path is a directory, not a file: `$sourcePath`."
            }

            val sourceContent = runCatching { Files.readString(sourcePath) }.getOrNull()
                ?: return@BridgeExpandingHandler "Could not read note at `$sourcePath`."

            val state = ClawDEASettings.getInstance().state
            val wikiRelativePath = "${state.claudeDirName}/${state.wikiSubdir}"

            PromoteToWikiPromptBuilder.build(
                sourceAbsolutePath = sourcePath.toString(),
                wikiRelativePath = wikiRelativePath,
                sourceContent = sourceContent,
            )
        }
}
