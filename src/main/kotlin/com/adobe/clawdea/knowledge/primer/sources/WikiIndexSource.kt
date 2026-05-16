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
package com.adobe.clawdea.knowledge.primer.sources

import com.adobe.clawdea.knowledge.primer.PrimerSource
import com.adobe.clawdea.knowledge.wiki.WikiPageReader
import com.adobe.clawdea.knowledge.wiki.WikiPath
import com.adobe.clawdea.settings.ClawDEASettings
import com.intellij.openapi.project.Project
import java.nio.file.Paths

class WikiIndexSource : PrimerSource {
    override val id = "wikiIndex"

    override fun load(project: Project): String? {
        val basePath = project.basePath ?: return null
        val state = ClawDEASettings.getInstance().state
        val wikiDir = Paths.get(basePath, state.claudeDirName, state.wikiSubdir)
        val reader = WikiPageReader(WikiPath(wikiDir))
        val index = reader.readIndex() ?: return null
        val directive = if (state.enableWikiLibrarian) {
            buildLibrarianDirective()
        } else {
            buildLegacyDirective(wikiDir.toString(), state.autoUpdateWiki)
        }
        return directive + "\n\n" + index
    }

    companion object {
        // History: sessions 9b36ff6b (#139), 537c8342 (#141), 1afd97af (#24),
        // and 2d41a87f (#86) all skipped the wiki under successively softer
        // wording. The hard-rule pattern (first call must be a wiki action,
        // exact tool named, alternatives explicitly listed) is what finally
        // worked. The v2 librarian directive evolves from the same lineage —
        // the first call moves from `search_wiki` to `Task(wiki-librarian)`,
        // but the hard-rule shape is preserved.

        internal fun buildLibrarianDirective(): String =
            """
                |## How this project's wiki works
                |
                |This project has a **wiki-librarian subagent** that holds the project's
                |design knowledge in its own fresh context every call. You ask it
                |questions; it answers from `.claude/wiki/`, verifies against current
                |source where it matters, and returns a synthesised answer with page
                |citations.
                |
                |**Hard rule: for any non-trivial question about how this project works** —
                |"where is X", "how does Y work", "what is the contract of Z", "why does
                |this do A instead of B" — your FIRST tool call must be:
                |
                |    Task(subagent_type="wiki-librarian", prompt="<the user's question, verbatim>")
                |
                |Not `Read`, not `search_text`, not `find_symbol`, not `Bash`. Exactly one
                |`Task` invocation. The librarian will name the files and entry points
                |to open; then the other tools are unrestricted.
                |
                |Two narrow exceptions:
                |
                |1. **You already have a wiki page slug.** If a previous turn or the
                |   librarian itself named `concepts/<slug>.md`, you can re-read it
                |   directly via `read_wiki_page(name='<slug>', kind='concept')` without
                |   a Task round-trip. The librarian is for *finding* and *synthesising*;
                |   direct read is for known pages.
                |
                |2. **Purely lexical edits.** Renames, formatting, lint where you already
                |   know the exact symbol or string. No design question = no librarian
                |   call.
                |
                |Below is the wiki index — use it to scope your question to the librarian
                |("how does the primer's wiki directive get built?" beats "wiki"). The
                |index is titles only; the actual knowledge is on the concept pages,
                |which only the librarian reads in a fresh context every time.
            """.trimMargin()

        internal fun buildLegacyDirective(wikiDir: String, autoUpdate: Boolean): String {
            val gapAction = if (autoUpdate) {
                "**write a new concept page** at `$wikiDir/concepts/<slug>.md` directly with the " +
                    "`Write` tool (auto-update is enabled — silent learning). Then append a " +
                    "matching bullet to `$wikiDir/index.md` with a standard Markdown link like " +
                    "`[Title](concepts/<slug>.md)`."
            } else {
                "**draft a new concept page** at `$wikiDir/concepts/<slug>.md` via " +
                    "`propose_write` so the user reviews the diff. Also extend `$wikiDir/index.md` " +
                    "with a matching bullet via `propose_edit` using a standard Markdown link like " +
                    "`[Title](concepts/<slug>.md)`."
            }
            return """
                |## How to use this wiki
                |
                |**Hard rule: your FIRST code-search tool call after reading the user message
                |must be a wiki probe.** Not `Read`, not `search_text`, not `find_files`, not
                |`Bash`. Exactly one `search_wiki` (1–3 keywords from the user's request) OR
                |one `read_wiki_page` (concept name from the index below). After that, the
                |other tools are unrestricted.
                |
                |`search_wiki` is for **orientation** — finding the right concept page that
                |names the files and entry points for a subsystem. `search_text` is for
                |**raw strings in code** — CLI flags, error messages, log lines. They are not
                |interchangeable; do the wiki probe regardless of how confident you feel
                |about a `search_text` plan.
                |
                |After the probe:
                |
                |1. **On hit:** read the page; it names the files, classes, and entry points
                |   to open. Use that to navigate instead of broad text search.
                |2. **On miss for a real subsystem** (multiple files, distinct responsibility),
                |   $gapAction Concept pages are ~150–250 lines: purpose, key files with
                |   line refs, control flow, gotchas. Skip page-creation only for one-file or
                |   purely lexical tasks (rename, format, lint).
                |
                |   Use standard Markdown links between wiki pages: from another concept page,
                |   `[Concept](concept.md)`; from the index, `[Concept](concepts/concept.md)`.
                |   Do not create new `[[concept]]` references.
                |3. Mention any wiki gaps you observed in your final reply so the user knows
                |   coverage improved (or where it still doesn't).
                |
                |The only tasks exempt from the probe are pure lexical edits (rename,
                |format, lint) where you already know the exact symbol or string to change.
            """.trimMargin()
        }
    }
}
