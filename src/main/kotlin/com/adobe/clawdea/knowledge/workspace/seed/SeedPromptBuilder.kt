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
package com.adobe.clawdea.knowledge.workspace.seed

import com.adobe.clawdea.knowledge.workspace.RepoGroup
import java.nio.file.Path

object SeedPromptBuilder {
    fun build(
        roots: List<Path>,
        suggestedGroups: List<RepoGroup>,
        suggestedDeps: List<CandidateClusterer.CrossGroupDep>,
        existingManifestText: String?,
    ): String = buildString {
        appendLine("Bootstrap a workspace manifest at <root>/.clawdea-workspace.md.")
        appendLine()
        appendLine("**Tool choice:** Use `propose_write` (`mcp__clawdea-intellij__propose_write`). Built-in `Write` is disabled.")
        appendLine()
        appendLine("**Step 1 — confirm the workspace root.** These ancestors qualified (deepest first):")
        for (r in roots) appendLine("- `$r`")
        appendLine()
        appendLine("Use `AskUserQuestion` to confirm. Default: the first.")
        appendLine()
        appendLine("**Step 2 — review suggested groups.**")
        if (suggestedGroups.isEmpty()) appendLine("(no candidates discovered)")
        else for (g in suggestedGroups) {
            appendLine("- `${g.name}` (${g.repos.size} repos):")
            for (r in g.repos) {
                val jira = if (r.jiraPrefixes.isEmpty()) "" else " _jira: ${r.jiraPrefixes.joinToString(",")}_"
                appendLine("    - **${r.key}** `${r.path}`$jira")
            }
        }
        appendLine()
        appendLine("**Step 2.5 — clustering principles.**")
        appendLine("Groups are *clusters* of related repos that you'd navigate as a unit. The use case: a session in `modules` should see `frontend` and `assets` listed in the same closure because they're all \"App product internals\" you'd reasonably navigate among.")
        appendLine()
        appendLine("`_dependsOn:` is for relationships *between groups*, not for expressing every repo→repo dependency. A 50-group manifest where every group is a single repo is a sign that runtime deps got encoded into group structure — that's wrong.")
        appendLine()
        appendLine("Prefer larger clusters; only split when README/CLAUDE.md narrative truly distinguishes the repos. `acme-app` vs. `acme-tooling` vs. `runtime-services` are real splits; `modules` vs. `frontend` is NOT — both are App product code that always travel together.")
        appendLine()
        appendLine("Worked example (canonical shape):")
        appendLine()
        appendLine("```markdown")
        appendLine("# Workspace: acme")
        appendLine()
        appendLine("## Repos: acme-app")
        appendLine("_dependsOn: runtime, storage_")
        appendLine()
        appendLine("- **modules** `modules` — Module Manager")
        appendLine("- **frontend** `frontend` — App frontend core")
        appendLine("- **assets** `assets` — Asset Catalog")
        appendLine("- **foundation** `foundation` — App Foundation components")
        appendLine()
        appendLine("## Repos: runtime")
        appendLine("_dependsOn: storage_")
        appendLine()
        appendLine("- **runtime-engine** `runtime-engine` — Runtime core")
        appendLine()
        appendLine("## Repos: storage")
        appendLine()
        appendLine("- **vault-impl** `vault-impl` — Storage / vault")
        appendLine("```")
        if (suggestedDeps.isNotEmpty()) {
            appendLine()
            appendLine("**Suggested cross-group dependencies** (FOR YOUR REFERENCE ONLY — do NOT transcribe this section into the manifest).")
            appendLine("Each line below shows a proposed edge plus the structural evidence behind it. Use the evidence to decide whether the edge is real; then express confirmed edges via `_dependsOn:` lines and discard the evidence text.")
            for (d in suggestedDeps) {
                appendLine("- `${d.from}` → `${d.to}` — evidence: ${d.evidence.joinToString(", ")}")
            }
            appendLine()
            appendLine("Validate each dep against READMEs/CLAUDE.md. When confirmed, write a `_dependsOn:` line directly under the group's `## Repos:` heading. Format: `_dependsOn: <CSV>_`.")
        }
        appendLine()
        appendLine("Read each candidate's `README.md` and `CLAUDE.md`. If the structural baseline produced too few clusters (everything in one group), split when narrative signals distinguish concerns. If it produced too many (every repo its own group), merge — singletons should be rare. Aim for 5-15 clusters in a typical workspace.")
        appendLine()
        appendLine("**Step 3 — write the manifest.** Format reference: `.claude/wiki/concepts/workspace-manifest.md`.")
        appendLine("Bullet grammar: `- **<key>** \\`<path>\\` — <role> _jira: <CSV>_` (separator MUST be em-dash U+2014).")
        appendLine()
        appendLine("**The manifest contains ONLY these elements** — nothing else:")
        appendLine("- a single `# Workspace: <name>` heading at the top")
        appendLine("- one or more `## Repos[: <name>]` group headings")
        appendLine("- optional `_dependsOn: <CSV>_` line directly under a group heading")
        appendLine("- repo bullets matching the bullet grammar above")
        appendLine()
        appendLine("DO NOT include `evidence:` lines, commentary, prose paragraphs, suggestion summaries, or any other content. The manifest is consumed by a strict regex parser; non-matching lines are silently dropped or break the parse.")
        if (existingManifestText != null) {
            appendLine()
            appendLine("**APPEND-ONLY.** A manifest already exists. Never edit, remove, or move existing entries.")
            appendLine("Add new repos to an existing group (when signals cohere) or as a new `## Repos: <name>` section.")
            appendLine()
            appendLine("Existing manifest:")
            appendLine("```markdown")
            appendLine(existingManifestText.trimEnd())
            appendLine("```")
        }
        appendLine()
        appendLine("Then call `propose_write` with the manifest content. The user will review the diff.")
    }
}
