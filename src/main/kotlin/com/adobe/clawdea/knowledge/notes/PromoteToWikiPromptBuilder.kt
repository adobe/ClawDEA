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

object PromoteToWikiPromptBuilder {
    fun build(
        sourceAbsolutePath: String,
        wikiRelativePath: String,
        sourceContent: String,
    ): String = buildString {
        appendLine("Promote this private note into the team wiki. The source note stays where it is — promotion is a publish event, not a move.")
        appendLine()
        appendLine("**Source note** (absolute path on disk: `$sourceAbsolutePath`):")
        appendLine()
        appendLine("```markdown")
        appendLine(sourceContent.trimEnd())
        appendLine("```")
        appendLine()
        appendLine("**Step 1 — orient.** Read `$wikiRelativePath/index.md`. Decide whether this content fits an existing concept page (append/edit) or warrants a new concept page under `$wikiRelativePath/concepts/`.")
        appendLine()
        appendLine("**Step 2 — tool choice.** Use `propose_edit` (`mcp__clawdea-intellij__propose_edit`) for existing pages and `propose_write` (`mcp__clawdea-intellij__propose_write`) for new pages. Built-in `Write`/`Edit` are not appropriate here even when auto-accept is enabled — promotion is a deliberate publish event and ALWAYS goes through diff review.")
        appendLine()
        appendLine("**Step 3 — index.** If you create a new concept page, also update `$wikiRelativePath/index.md` with a one-line entry under the appropriate section.")
        appendLine()
        appendLine("**Format guide.** Concept pages are 150–250 lines: purpose, key files with line refs (use `find_files`/`find_usages` to verify symbols), control flow, gotchas. Reshape the note's prose to fit — strip personal phrasing, keep technical content.")
        appendLine()
        appendLine("**Source preservation.** Do NOT delete or modify the source note at `$sourceAbsolutePath`. Only write to the wiki.")
    }
}
