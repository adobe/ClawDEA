# Drift detection

**Purpose** Keep the wiki and workspace manifest in sync with the codebase by detecting drift events on each rescan, auto-applying deterministic corrections, and routing the rest to the wiki-author subagent.

## Invariants

- `DriftDetectionService.rescan()` is the single entry point that produces `(events, applied)`. It runs synchronized on a single mutex; concurrent rescans serialize. All listeners are notified after each rescan completes ([DriftDetectionService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt)).
- Detector enablement is independent: `CodeRenameDetector` and `ManifestStaleDetector` always run; `CommitWikiDriftDetector` runs only when `enableWikiLibrarian` is true. Auto-apply is gated separately by `autoUpdateWiki`. Both flags must be considered when reasoning about why an event was or wasn't acted on ([DriftDetectionService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt)).
- `DriftAutoApplier.applyCodeRename` is intentionally conservative: it only rewrites when the broken link still appears verbatim in markdown-link parentheses. An out-of-date or already-applied event is a no-op, never a partial overwrite ([DriftAutoApplier.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftAutoApplier.kt)).
- A `CodeRename` event with a `suggestedReplacement` is auto-applied without invoking the wiki-author subagent. Only events the deterministic path cannot handle (no replacement, or `CommitDrift`, or `WikiSuggestion`) reach `WikiAuthorInvoker` ([DriftDetectionService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt), [WikiAuthorInvoker.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/WikiAuthorInvoker.kt)).
- Dismissed events (the user clicked the banner's "ignore") persist via `DriftStateStore`. The store is **mode-aware**: in default mode (no `.clawdea/config.json`) all state lives in a single `<wikiDir>/.drift-state.json`; in team mode the team-shared fields (`lastSyncedCommit`, `suggestions`) go to `<wikiDir>/.wiki-state.json` (git-tracked) and per-user fields (`lastScanAt`, `dismissed`, `probeMisses`, `userCorrections`) go to `<projectBase>/.clawdea/wiki-state.local.json` (gitignored). `filterDismissed` strips dismissed events before auto-apply, so a dismissed event is never re-applied or re-shown until its underlying signal changes ([DriftStateStore.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftStateStore.kt)).
- `CommitWikiDriftDetector` uses `lastSyncedCommit..HEAD` (resolved via `git merge-base --is-ancestor` to handle rebased-away SHAs) as the commit range. An empty or unreachable `lastSyncedCommit` is a first-run/no-baseline case that returns no events; the caller adopts current HEAD as the baseline. `lastSyncedCommit` semantically means "the commit the wiki currently describes" — `DriftDetectionService.shouldAdvanceSync` advances it **only** on first-run baseline (blank → HEAD) or when the rescan actually applied at least one event (deterministic auto-apply or wiki-author edit). Routine ticks where nothing was authored leave the SHA where it is, so the team-shared `.wiki-state.json` does not churn on every commit ([CommitWikiDriftDetector.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/CommitWikiDriftDetector.kt), [DriftDetectionService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt)).
- `rescan` and `dismiss` are no-ops when the repository is mid-rebase / mid-merge / mid-cherry-pick / mid-revert (`GitRepository.state != NORMAL`). Writing `.wiki-state.json` while a git operation is in progress would dirty the working tree and break `git rebase --continue` with "You have unstaged changes". The guard runs before any state mutation; `lastEvents` / `lastApplied` are returned unchanged so banner content stays stable across the interruption ([DriftDetectionService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt)).
- Team-mode migration is **idempotent and one-shot**: the first read in team mode where `<wikiDir>/.wiki-state.json` is absent splits a legacy `.drift-state.json` (looked up at both `<projectBase>/<claudeDirName>/<wikiSubdir>/` and the resolved `wikiDir`) into the new files and deletes the legacy file(s). If the team file already exists migration is skipped, so re-running rescan never clobbers updated team state ([DriftStateStore.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftStateStore.kt)).
- The wiki-author subagent edits files via the MCP `propose_*` tools — same pipeline as user-facing edits — so each edit still passes through the diff dialog when auto-accept is off. The subagent does **not** bypass edit review ([WikiAuthorInvoker.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/WikiAuthorInvoker.kt)).

## Resolution pipeline

1. **Trigger** — Called from `DriftStartupActivity` (project open), `RefreshWikiArgs` (`/refresh-wiki` command), `CommitWikiDriftDetector` (post-commit), or test fixtures.
2. **Resolve wiki path** — `WikiLocator.getInstance(project).wikiDir()` returns the active wiki directory (default `<projectBase>/<claudeDirName>/<wikiSubdir>` or, in team mode, `<projectBase>/<wikiPath>` from `.clawdea/config.json`). All detectors and the state store key off this single resolved path.
3. **Collect raw events** — `collectRaw` runs each detector in turn:
   - `CodeRenameDetector` — scans every `<wikiDir>/concepts/*.md`, parses `[label](../../../src/...)` links, flags links whose target file doesn't exist
   - `ManifestStaleDetector` — parses `.clawdea-workspace.md`, flags `path:` entries that don't resolve
   - `CommitWikiDriftDetector` — walks commits in `lastSyncedCommit..HEAD` via Git4Idea, intersects each commit's touched files with the per-page mention index, and emits one `CommitDrift` per affected page. First-run / unreachable SHA returns no events; HEAD becomes the new baseline.
4. **Filter dismissed** — `filterDismissed(raw, state)` removes events whose signature appears in `state.dismissed`.
5. **Auto-apply** — `applyAndDismiss(filtered, autoUpdateWiki, state, today, invoker)`:
   - For `CodeRename` with `suggestedReplacement`: `DriftAutoApplier.applyCodeRename` rewrites the link if it still matches verbatim
   - For `ManifestStale` with a resolved candidate: rewrites the manifest entry
   - Remaining events are batched into a digest and passed to `WikiAuthorInvoker.invoke(digest)`
6. **Bump synced commit & persist state** — `shouldAdvanceSync(state, applied)` decides whether to advance `lastSyncedCommit`: yes on first-run baseline (blank SHA) or when at least one event was applied; no on idle ticks. When advancing, `bumpSyncedCommit(newState, headSha)` rewrites the field (no-op when HEAD is blank). `DriftStateStore.write(wikiDir, projectBase, state)` then routes to either the legacy single file or the split team/per-user files depending on `.clawdea/config.json` presence. State is written only when `newStateWithSync != state`.
7. **Notify** — Listeners receive `(allEvents, appliedEvents)`. The chat panel's `DriftBanner` renders unapplied events; `ChatPanel` posts a one-line "auto-apply: N events" notice when `applied` is non-empty.

## Anti-patterns

- **Calling `applyCodeRename` without checking the link is still verbatim** — `DriftAutoApplier` does this internally. Bypassing the check rewrites links that have already been fixed by the user, producing double edits.
- **Adding a new detector and forgetting to handle dismissal** — Every event must produce a stable signature for `DriftStateStore` keying; otherwise a dismissed event will re-fire on every scan.
- **Letting `WikiAuthorInvoker` write directly via `Files.write`** — The subagent must use MCP `propose_edit` / `propose_write` so edits flow through the diff dialog and the user retains control. A direct write bypass undoes the entire safety contract.
- **Running `CommitWikiDriftDetector` without the `enableWikiLibrarian` gate** — The detector requires the wiki-librarian subagent to be available; running it otherwise produces unactionable events that pile up in the banner.
- **Writing to drift-state on every rescan** — The store only writes when `newStateWithSync != state` (after the `bumpSyncedCommit` step). Eager writes cause unnecessary FS churn, confuse external watchers, and in team mode produce noisy diffs on the git-tracked `.wiki-state.json`.
- **Hardcoding `.claude/wiki/.drift-state.json`** — The path is mode-dependent. Always go through `WikiLocator.getInstance(project).wikiDir()` and `DriftStateStore.read(wikiDir, projectBase)` so the team-mode split layout (and any custom `wikiPath`) is honored.
- **Persisting per-user fields into the team file** — `lastScanAt`, `dismissed`, `probeMisses`, and `userCorrections` are local; landing them in `.wiki-state.json` would commit personal scan state to git. The split in `DriftStateStore.writeTeam` enforces this — preserve it.
- **Filtering commits by `--since lastScanAt`** — That was the legacy approach; the current detector uses `lastSyncedCommit..HEAD` so branch switches and rebases produce correct ranges. Time-based filtering would re-fire events on every branch change.

## Source pointers

- [DriftDetectionService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt) — rescan orchestrator, listener fan-out, `shouldAdvanceSync` / `bumpSyncedCommit`, `isRepoBusy` rebase guard
- [GitRepositoryChangeSubscriber.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/GitRepositoryChangeSubscriber.kt) — debounced rescan trigger on git events
- [DriftPeriodicScanner.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftPeriodicScanner.kt) — 60-second interval rescan when `autoUpdateWiki` is on
- [WikiLocator.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiLocator.kt) — single source of truth for wiki path; default vs team mode resolution
- [WikiRelocateHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/commands/handlers/WikiRelocateHandler.kt) — `/wiki-relocate` opt-in to team mode (writes `.clawdea/config.json`, gitignores per-user file)
- [DriftAutoApplier.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftAutoApplier.kt) — deterministic auto-apply for `CodeRename` and `ManifestStale`
- [DriftEvent.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftEvent.kt) — sealed event hierarchy
- [DriftState.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftState.kt) / [DriftStateStore.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftStateStore.kt) — persistence of applied + dismissed events
- [CodeRenameDetector.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/CodeRenameDetector.kt) — broken source-link detector
- [ManifestStaleDetector.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/ManifestStaleDetector.kt) — workspace manifest path resolver
- [CommitWikiDriftDetector.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/CommitWikiDriftDetector.kt) — git-driven detector for load-bearing claim drift
- [WikiAuthorInvoker.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/WikiAuthorInvoker.kt) — subagent invocation for non-deterministic fixes
- [WikiAuthorDigestBuilder.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/WikiAuthorDigestBuilder.kt) — per-event digest passed to the subagent
- [DriftStartupActivity.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftStartupActivity.kt) — project-open trigger
- [DriftBanner.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/DriftBanner.kt) — chat-panel surface for unapplied events
