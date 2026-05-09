# Dream wiki maintenance

**Date:** 2026-05-09
**Status:** Spec, awaiting implementation plan
**Trigger:** Claude Code Dreams can run as background consolidation work. ClawDEA already has a project wiki whose purpose is to make the LLM more effective while keeping startup context small. Dreams should help maintain that wiki without turning it into a large, low-signal documentation dump.

## Goals

1. Use Dreams to maintain `.claude/wiki/` as LLM navigation context, not as general human documentation.
2. Extend the existing wiki drift workflow so Dream findings appear through the drift banner and `/refresh-wiki` review path.
3. Allow automatic application only for low-risk wiki maintenance, while substantive new knowledge and rewrites stay reviewable.
4. Keep maintenance cost bounded. Dream scans must use cheap gates and sampled/targeted inputs rather than full transcript rereads or precise token accounting.
5. Migrate wiki cross-references from `[[concept]]` syntax to standard Markdown links.

## Non-goals

- Replacing `CLAUDE.md`, `.claude/REPO_STATE.md`, personal notes, or the existing MCP wiki tools.
- Loading full concept pages into every prompt. The primer should continue to load only the wiki index and directives.
- Building a separate wiki-maintenance UI or command family.
- Computing exact token savings for each candidate. Cheap size guardrails are enough.
- Letting Dreams write arbitrary project code or unchecked wiki content.

## Current architecture

ClawDEA already uses a two-level knowledge model:

```
PrimerService
|-- ClaudeMdSource
|-- WikiIndexSource        # always-loaded wiki directive + index
|-- NotesSource
|-- SiblingsSource
`-- RepoStateSource

MCP tools
|-- search_wiki           # locate concept pages
`-- read_wiki_page        # load full concept/source/index pages on demand

Drift workflow
|-- DriftDetectionService
|-- DriftBanner
|-- /refresh-wiki
`-- DriftAutoApplier
```

That shape is still the right one. The index is small and always present so the LLM knows what concepts exist. Concept pages stay on disk and are read only when relevant.

## Proposed architecture

Dreams become a semantic drift source inside the existing drift framework.

```
ProjectOpenOrIdle
    |
    v
DriftDetectionService
    |
    +-- CodeRenameDetector
    +-- ManifestStaleDetector
    +-- DreamWikiDetector
            |
            +-- DreamDueGate
            +-- DreamInvocation
            +-- DreamOutputValidator
            +-- DreamCandidateScorer
            +-- DreamEventMapper
    |
    v
DriftBanner + /refresh-wiki
    |
    +-- low-risk auto-apply when enabled
    +-- diff review for substantive changes
```

`DriftDetectionService` remains the orchestrator. `DreamWikiDetector` adds richer wiki findings, but it does not create a second lifecycle, second banner, or separate maintenance queue.

## Dream triggers

Dream maintenance can run from three entry points:

1. **Startup due-check:** On project open, ClawDEA checks whether a Dream scan is due. The check must be cheap and should not run Dreams unless the gates pass.
2. **Idle background run:** When enough signal has accumulated and no interactive turn is active, ClawDEA may invoke Dreams in the background.
3. **Manual refresh:** `/refresh-wiki` gains parameters for Dream behavior, such as a status view and a forced Dream scan.

The default cadence is manual plus startup due-check plus idle background. A startup check may surface pending work without running a heavy scan.

## Due gates

Dream invocation requires all cheap gates to pass:

| Gate | Purpose |
|---|---|
| Knowledge layer enabled | Respect the existing main switch. |
| Dream maintenance enabled | Let users disable Dream wiki maintenance independently if needed. |
| Minimum elapsed time | Avoid running repeatedly in the same work period. |
| Enough new signal | Require new sessions, accepted wiki edits, probe misses, notes, or source changes since the last Dream run. |
| Scan throttle | Avoid repeated scans after a no-op result. |
| Filesystem lock | Prevent concurrent Dream runs in multiple ClawDEA windows. |
| No active interactive turn | Avoid competing with the foreground Claude Code process. |

The signal check should be approximate. It is enough to compare stored counters/timestamps and recent file mtimes; it should not parse every transcript just to decide whether a scan is worth running.

Initial defaults:

- minimum elapsed time: 24 hours since the last successful Dream scan
- enough new signal: 5 new signal units, where a signal unit is a new session, accepted wiki edit, probe miss, note update, or touched wiki/source-reference file
- scan throttle: 10 minutes since the last due-check or failed scan

These values should be settings-backed so they can be adjusted later without changing the architecture.

## Inputs to Dreams

The Dream prompt should be narrow and wiki-specific. It should inspect:

- `.claude/wiki/index.md`
- wiki page headings and short excerpts
- accepted `/learn` and `/promote-to-wiki` outcomes when available
- personal notes only when explicitly relevant to wiki promotion
- targeted transcript matches for corrections, repeated misses, and explicit memory signals
- source-file references already present in wiki pages
- current `REPO_STATE.md` for hot files and recent commits

The prompt should prefer grep-first and sampled inputs. It should not read all transcripts end-to-end.

## Structured output

Dreams should emit structured JSON, not prose instructions. ClawDEA validates the JSON before converting it to drift events.

Candidate shape:

```json
{
  "kind": "missingConcept|staleConcept|duplicateConcept|indexCleanup|linkNormalization|sourceReferenceFix",
  "title": "Short candidate title",
  "targetFiles": [".claude/wiki/index.md"],
  "evidence": [
    {
      "type": "sourceRef|sessionSignal|wikiProbeMiss|acceptedWikiChange|staleLink|duplicateContent",
      "ref": "path or stable identifier",
      "summary": "Why this evidence matters"
    }
  ],
  "usefulness": "How this helps future LLM navigation",
  "contextCost": "shrinks-context|neutral|adds-context",
  "confidence": "high|medium|low",
  "proposedAction": "applyLowRisk|proposeDiff|reportOnly",
  "patchPlan": "Concise description of the edit, not executable code"
}
```

Invalid JSON, unknown fields that change semantics, missing evidence, or unsupported candidate kinds should produce a status entry and no wiki edits.

## Drift events

Validated candidates map to typed drift events. Initial event types:

- `DreamIndexCleanup`: remove duplicate or stale index entries, shrink verbose index text, or normalize index links.
- `DreamLinkNormalization`: replace old `[[concept]]` references with standard Markdown links.
- `DreamSourceReferenceFix`: repair wiki links to moved or renamed source files when the target is unambiguous.
- `DreamDuplicateConcept`: identify pages that overlap enough to merge, usually review-only.
- `DreamStaleConcept`: identify concept pages contradicted by current source or accepted session corrections.
- `DreamMissingConcept`: propose a new concept page only when evidence shows repeated need.

Low-risk events can be auto-applied only when the edit is deterministic and bounded. New concept pages, large rewrites, and merges always go through review.

## Quality gates

Dreams must not create generic summaries. Each candidate needs evidence and a usefulness reason.

Valid evidence includes:

- source-file references and entry points
- repeated wiki probe misses for the same subsystem
- accepted `/learn` or `/promote-to-wiki` results
- stale source links
- duplicate or contradictory wiki pages
- recurring session corrections
- recent hot-file patterns from `REPO_STATE.md`

The scorer filters candidates using:

- future navigation value for the LLM
- specificity of named files, classes, and entry points
- freshness against current source
- duplication risk
- whether the candidate shrinks, preserves, or adds always-loaded context
- confidence that the evidence supports the proposed edit

The preferred order of maintenance is:

1. Shrink or sharpen the always-loaded index.
2. Fix broken or stale references.
3. Normalize wiki links to standard Markdown.
4. Merge or report duplicate concepts.
5. Add a new page only when future search reduction is clear.

## Context-cost guardrails

The system should not try to compute exact token savings. That would spend extra model work and may erase the benefit.

Use cheap guardrails instead:

- Count changed lines and characters in `.claude/wiki/index.md`, because it is always loaded.
- Enforce a soft cap for the index and concept pages.
- Classify each candidate as `shrinks-context`, `neutral`, or `adds-context`.
- Treat concept pages as on-demand context. Their value is judged by evidence and navigation usefulness, not exact token cost.
- Reject or report candidates that add index text without clear evidence.

Recommended initial caps:

- `index.md`: warn above 200 lines or 25 KB.
- concept pages: warn above 250 lines unless the page is intentionally a source/runbook page.
- one Dream run: cap candidate count before surfacing to the user; prefer the highest-evidence items.

## Auto-update policy

The existing `autoUpdateWiki` setting remains the main behavior switch.

When auto-update is off:

- All Dream events are review-only.
- `/refresh-wiki` should instruct Claude to use diff review tools for proposed edits.

When auto-update is on:

- Deterministic low-risk cleanup may apply silently.
- Auto-applied changes are reported as concise chat info lines.
- Substantive edits still require review.

Auto-applicable examples:

- normalize a single old-style wiki link when the target page exists
- remove a duplicate index entry
- update a source-file link when there is exactly one high-confidence replacement
- trim an index entry that repeats the linked page heading

Review-only examples:

- create a new concept page
- merge two concept pages
- rewrite a concept page based on session history
- delete a concept page
- change instructions in `WikiIndexSource`

## Commands and UX

Avoid command inflation. `/refresh-wiki` remains the wiki-maintenance command.

Supported parameter shape:

| Command | Behavior |
|---|---|
| `/refresh-wiki` | Rescan existing drift, include due Dream findings when already available. |
| `/refresh-wiki --dream` | Run a Dream scan now if no lock/turn conflict exists, then show pending events. |
| `/refresh-wiki --status` | Show last Dream run, due status, gate failures, filtered candidate counts, and pending events. |
| `/refresh-wiki --apply-low-risk` | Apply eligible low-risk events when auto-update is enabled; otherwise explain why review is required. |

Only add a separate command if it directly mirrors a Claude Code command, such as `/dream`. Wiki-specific behavior should stay under `/refresh-wiki`.

The drift banner stays concise:

```
wiki has 3 maintenance suggestions - /refresh-wiki to review - dismiss
```

The banner should not show Dream internals, scores, or long explanations.

## Markdown reference normalization

Standard Markdown links are the target wiki format. New generated wiki content should not use `[[concept]]`.

Migration behavior:

- In `index.md`, convert `[[concept]]` to `[Concept](concepts/concept.md)`.
- In concept pages, convert `[[other-concept]]` to `[Other Concept](other-concept.md)`.
- In source pages, convert links according to the source page location.
- Preserve old `[[concept]]` parsing temporarily in audit/search code until migration is complete.
- Update `/learn`, `/seed-wiki`, `/promote-to-wiki`, wiki primer directives, and Dream prompts to request standard Markdown links.

Auditing should support both formats during migration, but report old-style links as cleanup candidates.

## Failure handling

Dream maintenance is opportunistic. It must never block chat startup or an interactive turn.

Failure cases:

- Dreams unavailable in the installed Claude Code version
- Dream command exits non-zero
- timeout
- filesystem lock already held
- malformed JSON
- unsupported candidate kind
- missing evidence
- low confidence
- proposed edit exceeds size guardrails

All failures produce status/report information and no wiki edits. Repeated failures should be throttled to avoid noisy banners.

## State

Persist Dream maintenance state under `.claude/wiki/`, alongside the existing drift state. State should include:

- last Dream run timestamp
- last successful scan timestamp
- last scan status
- count or timestamp of processed signals
- dismissed event signatures
- lock metadata
- summary of filtered candidates for status reporting

State must not store full transcript content.

## Privacy and safety

Dream maintenance may inspect session signals, notes, and wiki content. It should keep writes limited to `.claude/wiki/` and drift state.

Personal notes are not automatically promoted. They can serve as evidence only when a candidate is still routed through review or when the note came from an already accepted promotion flow.

Dreams should not write project source files. Source files are evidence, not edit targets.

## Implementation surfaces

Implementation areas:

- `knowledge/drift`: due gates, Dream detector, event types, event validation, auto-apply rules.
- `knowledge/wiki`: Markdown link parsing, link normalization, audit compatibility.
- `chat/ChatPanel.kt`: `/refresh-wiki` parameter parsing and prompt expansion.
- `chat/DriftBanner.kt`: concise wording for semantic maintenance suggestions.
- `knowledge/primer/sources/WikiIndexSource.kt`: update directives to reference standard Markdown links and the Dream maintenance review flow.
- `knowledge/notes/PromoteToWikiPromptBuilder.kt`: replace `[[wikilinks]]` guidance with standard Markdown links.
- tests under `src/test/kotlin/com/adobe/clawdea/knowledge/`.

## Validation plan

Unit tests should cover:

- Dream due-gate pass/fail cases.
- Lock handling and scan throttling.
- JSON validation and rejection of malformed or unsupported candidates.
- Candidate scoring and filtering.
- Mapping Dream candidates to drift events.
- Auto-apply boundaries for low-risk versus review-only events.
- `/refresh-wiki` parameter parsing.
- Markdown-link normalization from old wiki syntax.
- Wiki auditor compatibility with both old and new link formats during migration.
- Status reporting for unavailable Dreams, timeouts, and filtered candidates.

Integration tests should verify:

- Dream events appear through the existing drift banner.
- `/refresh-wiki --dream` surfaces pending events without editing source code.
- Auto-update applies only deterministic low-risk cleanup.
- Substantive changes still route through diff review.

## Implementation decisions

- Add a `DreamInvocation` boundary so CLI/API details are isolated from drift logic. The implementation should probe the installed Claude Code capability once per process and report `Dreams unavailable` when the feature is missing.
- Use the initial due-gate defaults in this spec for the first implementation, with settings-backed thresholds for later tuning.
- Keep wiki-specific Dream behavior under `/refresh-wiki --dream`. Do not add a separate wiki command. If Claude Code exposes `/dream`, ClawDEA may bridge `/dream` only as a general Claude Code mirror, not as another wiki-maintenance command.
