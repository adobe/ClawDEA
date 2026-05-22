# Drift detection

**Purpose** Keep the wiki and workspace manifest in sync with the codebase by detecting drift events on each rescan, auto-applying deterministic corrections, and routing the rest to the wiki-author subagent.

## Invariants

- `DriftDetectionService.rescan()` is the single entry point that produces `(events, applied)`. It runs synchronized on a single mutex; concurrent rescans serialize. All listeners are notified after each rescan completes ([DriftDetectionService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt)).
- Detector enablement is independent: `CodeRenameDetector` and `ManifestStaleDetector` always run; `CommitWikiDriftDetector` runs only when `enableWikiLibrarian` is true. Auto-apply is gated separately by `autoUpdateWiki`. Both flags must be considered when reasoning about why an event was or wasn't acted on ([DriftDetectionService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt)).
- `DriftAutoApplier.applyCodeRename` is intentionally conservative: it only rewrites when the broken link still appears verbatim in markdown-link parentheses. An out-of-date or already-applied event is a no-op, never a partial overwrite ([DriftAutoApplier.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftAutoApplier.kt)).
- A `CodeRename` event with a `suggestedReplacement` is auto-applied without invoking the wiki-author subagent. Only events the deterministic path cannot handle (no replacement, or `CommitDrift`, or `WikiSuggestion`) reach `WikiAuthorInvoker` ([DriftDetectionService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt), [WikiAuthorInvoker.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/WikiAuthorInvoker.kt)).
- Dismissed events (the user clicked the banner's "ignore") persist in `.claude/wiki/.drift-state.json` via `DriftStateStore`. `filterDismissed` strips them before auto-apply, so a dismissed event is never re-applied or re-shown until its underlying signal changes ([DriftStateStore.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftStateStore.kt)).
- The wiki-author subagent edits files via the MCP `propose_*` tools — same pipeline as user-facing edits — so each edit still passes through the diff dialog when auto-accept is off. The subagent does **not** bypass edit review ([WikiAuthorInvoker.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/WikiAuthorInvoker.kt)).

## Resolution pipeline

1. **Trigger** — Called from `DriftStartupActivity` (project open), `RefreshWikiArgs` (`/refresh-wiki` command), `CommitWikiDriftDetector` (post-commit), or test fixtures.
2. **Collect raw events** — `collectRaw` runs each detector in turn:
   - `CodeRenameDetector` — scans every `.claude/wiki/concepts/*.md`, parses `[label](../../../src/...)` links, flags links whose target file doesn't exist
   - `ManifestStaleDetector` — parses `.clawdea-workspace.md`, flags `path:` entries that don't resolve
   - `CommitWikiDriftDetector` — runs `git log` since the last scan, compares touched files to wiki source-pointer maps, flags pages whose load-bearing claims may have drifted
3. **Filter dismissed** — `filterDismissed(raw, state)` removes events whose signature appears in `state.dismissed`.
4. **Auto-apply** — `applyAndDismiss(filtered, autoUpdateWiki, state, today, invoker)`:
   - For `CodeRename` with `suggestedReplacement`: `DriftAutoApplier.applyCodeRename` rewrites the link if it still matches verbatim
   - For `ManifestStale` with a resolved candidate: rewrites the manifest entry
   - Remaining events are batched into a digest and passed to `WikiAuthorInvoker.invoke(digest)`
5. **Persist state** — Successfully applied events get added to `state.applied`; their signatures prevent re-firing on the next scan. State is written to `.drift-state.json` only if it changed.
6. **Notify** — Listeners receive `(allEvents, appliedEvents)`. The chat panel's `DriftBanner` renders unapplied events; `ChatPanel` posts a one-line "auto-apply: N events" notice when `applied` is non-empty.

## Anti-patterns

- **Calling `applyCodeRename` without checking the link is still verbatim** — `DriftAutoApplier` does this internally. Bypassing the check rewrites links that have already been fixed by the user, producing double edits.
- **Adding a new detector and forgetting to handle dismissal** — Every event must produce a stable signature for `DriftStateStore` keying; otherwise a dismissed event will re-fire on every scan.
- **Letting `WikiAuthorInvoker` write directly via `Files.write`** — The subagent must use MCP `propose_edit` / `propose_write` so edits flow through the diff dialog and the user retains control. A direct write bypass undoes the entire safety contract.
- **Running `CommitWikiDriftDetector` without the `enableWikiLibrarian` gate** — The detector requires the wiki-librarian subagent to be available; running it otherwise produces unactionable events that pile up in the banner.
- **Writing to `.drift-state.json` on every rescan** — The store only writes when `newState != state`. Eager writes cause unnecessary FS churn and confuse external watchers.

## Source pointers

- [DriftDetectionService.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftDetectionService.kt) — rescan orchestrator, listener fan-out
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
