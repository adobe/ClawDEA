# Knowledge layer

**Purpose** Ship a curated, project-local context bundle ("the primer") with every CLI turn so Claude starts each conversation already oriented to this codebase, plus on-demand wiki pages, personal notes, and cross-repo workspace navigation.

## Invariants

- The primer assembly **order is load-bearing**: `ClaudeMd → WikiIndex → Notes → Siblings → RepoState`. The wiki index must land **before** the repo-state snapshot, otherwise hot-file proximity makes the wiki feel redundant and the agent skips the wiki probe (observed across sessions 9b36ff6b, 537c8342, 1afd97af, 2d41a87f) ([PrimerService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/primer/PrimerService.kt)).
- Each primer source is wrapped in `<primer source="<id>">…</primer>` tags by `PrimerAssembler`. Empty/blank bodies are skipped, not emitted as empty tags ([PrimerAssembler.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/primer/PrimerAssembler.kt)).
- The freshly-generated `REPO_STATE` text is preferred over the on-disk copy when both are available. If `RepoStateWriter` failed (bad permissions, FS error), reading from disk would yield a stale or empty body while the in-memory text is correct ([PrimerService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/primer/PrimerService.kt)).
- The entire knowledge layer is gated by `enableKnowledgeLayer`. When disabled, `PrimerService.refreshAndGet()` returns an empty string and the CLI starts with no primer — the only contract is "knowledge-layer off → zero-cost primer" ([PrimerService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/primer/PrimerService.kt)).
- Concept pages live under `<wikiDir>/concepts/<slug>.md`, three levels below the wiki root. Source-file links must therefore be `../../../src/...` relative paths — anything else breaks click-through and drift detection ([CodeRenameDetector.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/CodeRenameDetector.kt)).
- Concept pages are **pulled on demand** by `read_wiki_page` / `search_wiki` (MCP tools), not preloaded into the primer. Only the index TOC ships with every turn — concept content loads only when Claude probes it ([WikiIndexSource.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/primer/sources/WikiIndexSource.kt), [McpWikiTools.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpWikiTools.kt)).
- The wiki path is resolved by **`WikiLocator`** (project service) — a single source of truth. In default mode (no `<projectBase>/.clawdea/config.json`) it returns `<projectBase>/.clawdea/<wikiSubdir>` (default `.clawdea/wiki/`; the `wikiSubdir` is configurable in `ClawDEASettings`). In team mode (config file present with non-blank `wikiPath`) it returns `<projectBase>/<wikiPath>`. All wiki-aware code (primer sources, drift detection, MCP wiki tools, chat panel, `/promote-to-wiki`) routes through `WikiLocator.getInstance(project).wikiDir()` — never construct the wiki path manually ([WikiLocator.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiLocator.kt)).
- `RepoStateGenerator` runs **before** any source fetch on each `refreshAndGet()`. If it exceeds `repoStateWarnThresholdMs` (settings-tunable), a warn-level log fires; the primer still ships, but persistent slow regen is a maintenance signal ([PrimerService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/primer/PrimerService.kt)).

## Resolution pipeline

1. **CLI start** — `CliProcess.start()` calls `PrimerService.refreshAndGet()` while assembling the system prompt.
2. **Generate REPO_STATE** — `RepoStateGenerator.generate()` runs each section generator (`MavenSectionGenerator`, `GitSectionGenerator`) and assembles the timestamped markdown. Result both:
   - written to `.clawdea/REPO_STATE.md` via `RepoStateWriter`
   - kept in memory for direct injection into the primer
3. **Fetch sources in declared order** — `PrimerService` iterates `sources` (`ClaudeMdSource`, `WikiIndexSource`, `NotesSource`, `SiblingsSource`, `RepoStateSource`):
   - each `source.load(project)` returns nullable text
   - `RepoStateSource` is special-cased: prefer the in-memory text over `source.load`
   - any throwing source is logged and treated as missing, not fatal
4. **Assemble** — `PrimerAssembler.assemble(parts)` produces the final `<primer source="...">` block in declared order, skipping empty bodies.
5. **Cache** — assembled payload is stored in `cached: AtomicReference<String>`; subsequent reads without a refresh return the cached copy.
6. **CLI consumption** — the primer is appended to the system prompt file (after MCP + edit-review prompts, after skill catalog) and shipped to the CLI via `--append-system-prompt-file`.
7. **On-demand wiki probe (during a turn)** — Claude calls MCP `read_wiki_page` or `search_wiki`; `McpWikiTools` resolves `WikiLocator.getInstance(project).wikiDir()` and reads from `<wikiDir>/concepts/`, returning the page body.

## Team mode (wiki path)

Default-mode users keep `<projectBase>/.clawdea/wiki/` and a single `.drift-state.json`. (A legacy `.claude/wiki/` from older versions is migrated to `.clawdea/wiki/` silently on project open by `ClawdeaArtifactMigrator`.) Opting in via the `/wiki-relocate <repo-relative-path>` slash command (handled by `WikiRelocateHandler`) writes `<projectBase>/.clawdea/config.json` with `{"wikiPath":"<path>"}`, optionally `git mv`s the existing wiki tree to the new path, and appends `.clawdea/wiki-state.local.json` to `.gitignore`. Because `.clawdea/config.json` is git-tracked, teammates auto-discover the wiki path on clone — no manual setup. `DriftStateStore` then splits its persistence into a team-shared file (`<wikiDir>/.wiki-state.json`, holding `lastSyncedCommit` + `suggestions`) and a per-user file (`<projectBase>/.clawdea/wiki-state.local.json`, holding `lastScanAt`, `dismissed`, `probeMisses`, `userCorrections`); the first read in team mode performs an idempotent migration from the legacy single-file format. See [Drift detection](drift-detection.md) for the persistence and commit-range invariants.

## Anti-patterns

- **Reordering primer sources** — Especially moving `WikiIndexSource` after `RepoStateSource` re-introduces the wiki-skip regression. The order is documented at the call site for a reason.
- **Inlining concept-page content into the primer** — Concept pages are pulled on demand because shipping all of them every turn blows the prompt budget. Only the TOC ships.
- **Reading `REPO_STATE.md` from disk in `PrimerService`** — Use the freshly-generated in-memory text. Disk reads risk staleness when the writer just failed.
- **Hard-failing on a source exception** — One bad source must not block the primer. Sources are wrapped in try/catch and logged at warn level.
- **Writing `{[ref:...|...]}` chat-only ref syntax into wiki markdown** — That syntax is for chat HTML cards. Wiki concept pages use standard Markdown links so the file is portable and drift detection can parse them.

## Source pointers

- [PrimerService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/primer/PrimerService.kt) — orchestrator: regen REPO_STATE, fetch sources, assemble, cache
- [PrimerAssembler.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/primer/PrimerAssembler.kt) — `<primer source>` wrapping with empty-body skip
- [PrimerSource.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/primer/PrimerSource.kt) — source interface
- [ClaudeMdSource.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/primer/sources/ClaudeMdSource.kt), [WikiIndexSource.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/primer/sources/WikiIndexSource.kt), [SiblingsSource.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/primer/sources/SiblingsSource.kt), [RepoStateSource.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/primer/sources/RepoStateSource.kt) — concrete sources
- [NotesSource.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/notes/NotesSource.kt) — `.claude/notes/CURRENT.md` source
- [RepoStateGenerator.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/repostate/RepoStateGenerator.kt), [RepoStateWriter.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/repostate/RepoStateWriter.kt) — REPO_STATE assembly
- [WikiPageReader.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiPageReader.kt), [WikiSearcher.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiSearcher.kt) — concept-page lookup backing `read_wiki_page` / `search_wiki`
- [WikiLocator.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiLocator.kt) — single source of truth for wiki path; default vs team mode resolution
- [WikiRelocateHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/handlers/WikiRelocateHandler.kt) — `/wiki-relocate` opt-in to team mode
- [WorkspaceManifest.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/workspace/WorkspaceManifest.kt) — `.clawdea-workspace.md` parser; powers cross-repo navigation
- [McpWikiTools.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpWikiTools.kt), [McpWorkspaceTools.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpWorkspaceTools.kt), [McpPrimerTool.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpPrimerTool.kt) — MCP entry points
