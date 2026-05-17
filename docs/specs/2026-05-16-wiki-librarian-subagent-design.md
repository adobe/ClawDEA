# Wiki Librarian Subagent — Design

| | |
|---|---|
| **Date** | 2026-05-16 |
| **Status** | Implemented (with deviations — see [`2026-05-17-wiki-maintenance-redesign-design.md`](2026-05-17-wiki-maintenance-redesign-design.md) for the current state). The `WikiLibrarianInstaller` from this spec was replaced by per-session `--agents` JSON injection (commits `baab575`, `841cb5a`). |
| **Scope** | Sub-agent framework (via Claude Code subagents) + wiki librarian v1 over the existing `.claude/wiki/` layout |
| **Defers to later specs** | Distributed wiki storage; permission-judge agent; adversarial-reviewer agent; `wiki-author` subagent for silent suggestion authoring |

## Problem

The current wiki has three failure modes:

1. **Brittle search.** `WikiSearcher` does literal lowercased substring matching of the full query against each `.md` file. Multi-word or conceptual queries miss whenever the exact phrase isn't present, even when a relevant concept page exists. Confirmed in `WikiSearcher.kt`.
2. **Context decay.** Only `index.md` (~1.4 KB of bullet titles) is in the primer. Concept pages enter the main chat's context only when explicitly read, and once in, they age out as the conversation grows. Late turns lose the wiki.
3. **Shallow content.** The directive in `WikiIndexSource.buildDirective()` has already been hardened across four sessions (the comment lists 9b36ff6b, 537c8342, 1afd97af, 2d41a87f) to force a turn-1 probe. First-turn adherence is near the ceiling; what's missing is a mechanism that keeps the wiki active across the whole conversation and that doesn't rely on substring matching.

## Goals

- Replace the main agent's `search_wiki` keyword probe with delegation to a dedicated **wiki-librarian subagent** that holds wiki content in a fresh LLM context per call. The main chat's context stays free of wiki content; the librarian never decays because it has no "later."
- Make the librarian's answers grounded — it can verify wiki claims against current source via IntelliJ-backed and filesystem tools before answering.
- Let the librarian feed observed wiki gaps into the existing maintenance pipeline (`DriftDetector` / `DreamWikiDetector` / `/refresh-wiki`) via a suggestion queue, without giving it write access to wiki files.
- Use Claude Code's native subagent mechanism (`.claude/agents/*.md` + `Task` tool) as the framework substrate. Do not build a custom sub-agent runtime.
- Default on; opt-out via setting.

## Non-goals

- Distributed wiki storage (documents next to source). Deferred.
- Silent LLM authoring of wiki pages. The librarian *only* records suggestions; authoring stays user-initiated in v1.
- Replacing `DreamWikiDetector`. It continues to run independently; the librarian feeds the same suggestion namespace.
- Other agents (permission-judge, adversarial reviewer). Each gets its own spec.

## Architecture

Three project-local artefacts compose with existing services:

```
User question
  → main agent reads primer (index TOC + new directive)
  → main agent invokes Task(subagent_type="wiki-librarian", prompt="<question>")
       [fresh LLM context, allowlisted tools only]
        → librarian: first call = read_wiki_page(name='index', kind='index')
        → librarian: reads 1-3 concept pages it identifies
        → librarian: optionally verifies via Read / find_symbol / search_text
        → librarian: optionally calls record_wiki_suggestion (writes queue)
        → librarian: returns short answer with citations
  → main agent receives the answer, continues with the user's task

Out-of-band drain (unchanged trigger paths):
  /refresh-wiki  OR  DreamWikiDetector scheduled scan
    → DriftDetectionService.runOnce()
         → loads DriftEvents + WikiSuggestions from .drift-state.json
         → applyAndDismiss(autoUpdateWiki)
             → CodeRename / ManifestStale: existing auto-apply behaviour
             → WikiSuggestion: always returns to "remaining"; never auto-applied
         → notification UI surfaces remaining items
```

## Component 1 — agent file (`.claude/agents/wiki-librarian.md`)

Stored as a plugin resource at `src/main/resources/agents/wiki-librarian.md`. Installed into each project via `WikiLibrarianInstaller` (Component 7). Canonical content:

```markdown
---
name: wiki-librarian
description: |
  Answers questions about this project's design, subsystems, and conventions
  from .claude/wiki/. Use for any "how does X work" / "where is Y" /
  "what is the contract of Z" question about this codebase before doing
  your own code search. Returns a synthesised answer with page citations;
  may log a wiki suggestion for the user to review at refresh time.
tools:
  - Read
  - mcp__clawdea-intellij__read_wiki_page
  - mcp__clawdea-intellij__search_text
  - mcp__clawdea-intellij__find_files
  - mcp__clawdea-intellij__find_symbol
  - mcp__clawdea-intellij__find_usages
  - mcp__clawdea-intellij__find_callers
  - mcp__clawdea-intellij__resolve_symbol
  - mcp__clawdea-intellij__find_diagnostics
  - mcp__clawdea-intellij__record_wiki_suggestion
---

You are this project's **wiki librarian**. Your only job is to answer the
calling agent's question by consulting `.claude/wiki/` and, where it
matters, verifying against current source.

## Workflow

1. **Read the index first.** Your first tool call MUST be
   `read_wiki_page(name='index', kind='index')`.
2. **Pick relevant pages.** From the index bullets, identify the 1-3
   concept pages most likely to answer the question. Read them with
   `read_wiki_page(name='<slug>', kind='concept')`. Do not speculate from
   index titles - always open the page.
3. **Verify load-bearing claims.** Confirm "X is in file Y" / "M does N"
   with `find_symbol` / `find_usages` / `Read`. Never assert what you
   couldn't verify.
4. **Spot gaps.** If the question is about a real subsystem with no
   concept page, OR you found a wiki claim contradicted by current
   source, call `record_wiki_suggestion` before answering. One per gap.
5. **Answer** in 1-3 short paragraphs, citing pages by relative path.

## Hard constraints

- No write tools. `record_wiki_suggestion` is your only write path.
- No `Bash`, `Edit`, `Write`, `propose_*`. Not in your allowlist.
- Synthesise; do not repeat pages verbatim.
- Only read pages the index says are relevant.
- If wiki and source both lack the answer, say so and name the closest pages.
```

The agent file's body additionally instructs the librarian on `record_wiki_suggestion` field semantics and output shape; the field rules themselves are normative in Component 2 (MCP tool registration + validation).

**Tool-name prefix.** MCP tools are referenced as `mcp__<server-name>__<tool-name>`. The server name is `clawdea-intellij` (confirmed in `McpClientConfig.kt` and `McpProtocol.toolsListResponse`'s `serverInfo.name`).

## Component 2 — `WikiSuggestion` type + `record_wiki_suggestion` MCP tool

New variant added to the existing `DriftEvent` sealed class in `src/main/kotlin/com/adobe/clawdea/knowledge/drift/DriftEvent.kt`:

```kotlin
sealed class DriftEvent {
    abstract val signature: String

    data class CodeRename(...) : DriftEvent()       // unchanged
    data class ManifestStale(...) : DriftEvent()    // unchanged

    data class WikiSuggestion(
        val kind: SuggestionKind,
        val title: String,
        val rationale: String,
        val targetFiles: List<Path>,
        val sourcePage: Path?,
        val recordedAt: Instant,
    ) : DriftEvent() {
        override val signature: String =
            "wiki-suggestion:${kind.name}:${primaryTarget(targetFiles)}"
    }
}

enum class SuggestionKind { missingConcept, staleConcept, incompleteConcept }
```

`primaryTarget` picks the most specific path in `targetFiles`, excluding `.claude/wiki/index.md` (every new concept touches the index → not a useful dedup key).

**Dedup namespace shared with Dream.** `DreamWikiDetector` must build matching signatures for `missingConcept` / `staleConcept` so a librarian-spotted gap collapses with a later Dream-spotted one (latest `recordedAt` wins). Implementation alignment task: route Dream's candidate-to-event conversion through the same signature builder.

**MCP tool registration** in `McpWikiTools.kt`:

```kotlin
router.register(
    name = "record_wiki_suggestion",
    description = "Record a proposed wiki improvement (missing/stale/incomplete " +
        "concept) for the user to review at wiki refresh time. Use sparingly - " +
        "one per distinct gap. Not surfaced to the main chat; only the " +
        "wiki-librarian subagent's allowlist contains this tool.",
    properties = listOf(
        Triple("kind", "string", "One of missingConcept, staleConcept, incompleteConcept"),
        Triple("title", "string", "3-7 word title for the proposed change"),
        Triple("rationale", "string", "1-2 sentence explanation of what was observed"),
        Triple("target_files", "string", "Comma-separated wiki paths the change would touch"),
        Triple("source_page", "string", "Optional: wiki page consulted when the gap was noticed"),
    ),
    required = listOf("kind", "title", "rationale", "target_files"),
    handler = ::recordWikiSuggestion,
)
```

**Handler responsibilities.** Parse + validate args (see below), build a `DriftEvent.WikiSuggestion` with `recordedAt = Instant.now()`, load `.drift-state.json`, check signature against `state.dismissed` (return `{"status":"dismissed"}` without persisting) and `state.suggestions` (update timestamp + title + rationale on match) else append, atomic write, return `{"status":"recorded","signature":"..."}`.

A small helper class `WikiSuggestionWriter` under `knowledge/wiki/` encapsulates the load/validate/persist dance to keep the MCP handler thin and unit-testable.

**Validation rules (trust boundary).**

- `kind` must parse to `SuggestionKind` enum; else error.
- `title`: 3 ≤ length ≤ 120.
- `rationale`: 10 ≤ length ≤ 800.
- `target_files`: non-empty; every entry must be under `.claude/wiki/`, end in `.md`, contain no `..`. Reuse the path-safety check pattern from `WikiPath.subPath()`.
- `source_page` (if present): same path-safety check, must be under `.claude/wiki/`.

Validation failures return `isError = true` with a clear message. No partial writes.


## Component 3 — `DriftStateStore` schema bump + `DriftAutoApplier` extension

Bump persisted schema to `version: 2`:

```jsonc
{
  "version": 2,
  "dismissed": [...],            // unchanged
  "applied": [...],              // unchanged
  "dreamLockOwner": "...",       // unchanged
  "suggestions": [               // NEW
    {
      "kind": "missingConcept",
      "title": "Add concept page for FilesystemRefreshCoordinator",
      "rationale": "...",
      "targetFiles": [".claude/wiki/concepts/filesystem-refresh-coordinator.md",
                      ".claude/wiki/index.md"],
      "sourcePage": null,
      "recordedAt": "2026-05-16T16:30:00Z",
      "signature": "wiki-suggestion:missingConcept:concepts/filesystem-refresh-coordinator.md"
    }
  ]
}
```

`DriftStateStore` reads both v1 (missing `suggestions` → empty list) and v2. New writes always use v2.

**`DriftStateStore.dismiss(state, signature)` extension** — when dismissing, also strip any matching entry from `state.suggestions`, otherwise the next `runOnce()` reloads the entry from state and `filterDismissed` silently drops it on every cycle:

```kotlin
fun dismiss(state: DriftState, signature: String): DriftState =
    state.copy(
        dismissed = state.dismissed + signature,
        suggestions = state.suggestions.filterNot { it.signature == signature },
    )
```

**`DriftDetectionService.runOnce()`** loads pending suggestions from state and joins the event list before `applyAndDismiss`:

```kotlin
val state = DriftStateStore.read(claudeDir)
val raw = collectRaw(Paths.get(basePath), claudeDir).toMutableList()
raw += state.suggestions
val filtered = filterDismissed(raw, state)
val (remaining, applied) = applyAndDismiss(filtered, autoUpdate, state, today)
```

**`DriftAutoApplier.applyAndDismiss`** adds a third branch:

```kotlin
when (event) {
    is DriftEvent.CodeRename     -> /* existing auto-apply */
    is DriftEvent.ManifestStale  -> /* existing auto-apply */
    is DriftEvent.WikiSuggestion -> remaining += event   // v1: always surface
}
```

`WikiSuggestion` is never auto-applied regardless of `autoUpdateWiki`. The setting affects only *cadence* of surfacing:

| Setting | WikiSuggestion behaviour |
|---|---|
| `autoUpdateWiki=true` | Surfaces in the existing periodic Dream-scan notification cycle |
| `autoUpdateWiki=false` | Sits in `.drift-state.json` until `/refresh-wiki` is invoked |

Rationale: applying a `WikiSuggestion` means authoring real prose, qualitatively different from the mechanical link/path rewrites today's auto-apply handles. Silent LLM authoring is deferred to a future `wiki-author` subagent.

## Component 4 — primer directive rewrite (`WikiIndexSource`)

`WikiIndexSource.buildDirective(wikiDir, autoUpdate)` becomes `buildDirective()` (both old params were only used by the now-removed "write a new page" guidance). The function selects between two directives based on `ClawDEASettings.enableWikiLibrarian`:

- `enableWikiLibrarian=true` (default): emits the librarian directive (see below).
- `enableWikiLibrarian=false`: emits `buildLegacyDirective()`, which preserves today's verbatim wording. This is the opt-out escape hatch (Component 7).

The existing comment block documenting sessions `9b36ff6b`, `537c8342`, `1afd97af`, `2d41a87f` is preserved and extended with a note that the v2 directive evolved from the same hard-rule lineage, with the first call moving from `search_wiki` to `Task(wiki-librarian)`.

**Librarian directive (proposed text):**

```text
## How this project's wiki works

This project has a **wiki-librarian subagent** that holds the project's
design knowledge in its own fresh context every call. You ask it
questions; it answers from `.claude/wiki/`, verifies against current
source where it matters, and returns a synthesised answer with page
citations.

**Hard rule: for any non-trivial question about how this project works** -
"where is X", "how does Y work", "what is the contract of Z", "why does
this do A instead of B" - your FIRST tool call must be:

    Task(subagent_type="wiki-librarian", prompt="<the user's question, verbatim>")

Not `Read`, not `search_text`, not `find_symbol`, not `Bash`. Exactly one
`Task` invocation. The librarian will name the files and entry points to
open; then the other tools are unrestricted.

Two narrow exceptions:

1. **You already have a wiki page slug.** If a previous turn or the
   librarian itself named `concepts/<slug>.md`, you can re-read it
   directly via `read_wiki_page(name='<slug>', kind='concept')` without
   a Task round-trip. The librarian is for *finding* and *synthesising*;
   direct read is for known pages.

2. **Purely lexical edits.** Renames, formatting, lint where you already
   know the exact symbol or string. No design question = no librarian
   call.

Below is the wiki index - use it to scope your question to the librarian
("how does the primer's wiki directive get built?" beats "wiki"). The
index is titles only; the actual knowledge is on the concept pages,
which only the librarian reads in a fresh context every time.
```

The existing `.claude/wiki/index.md` body follows unchanged.


## Component 5 — `McpWikiTools.kt` surgery

Three changes in `registerAll()`, gated on `ClawDEASettings.enableWikiLibrarian`:

```kotlin
fun registerAll(router: McpToolRouter) {
    val state = ClawDEASettings.getInstance().state
    router.register(name = READ_TOOL_NAME, ...)              // always — main-chat escape hatch + librarian allowlist
    router.register(name = "record_wiki_suggestion", ...)    // always (harmless if librarian off — no caller)
    if (!state.enableWikiLibrarian) {
        router.register(name = SEARCH_TOOL_NAME, ...)        // legacy escape hatch only
    }
}
```

When `enableWikiLibrarian=true`, `search_wiki` is not exposed to anyone — neither the main agent (its primer doesn't mention it) nor the librarian (its `tools:` allowlist doesn't include it). When `enableWikiLibrarian=false`, the legacy directive + legacy tool are both available.

`WikiSearcher.kt` and `WikiSearcherTest.kt` are **kept** (revising an earlier draft that proposed deletion). They back the `search_wiki` legacy path, which the opt-out setting must keep functional.

The `private fun searchWiki(...)` method in `McpWikiTools.kt` stays; only its registration becomes conditional.

## Component 6 — `WikiLibrarianInstaller`

New class under `knowledge/wiki/` that ensures `.claude/agents/wiki-librarian.md` exists in a project. Canonical text lives in `src/main/resources/agents/wiki-librarian.md` (ships in plugin jar; edits with normal markdown tooling).

```kotlin
class WikiLibrarianInstaller {
    sealed class InstallResult {
        object AlreadyPresent : InstallResult()
        object Installed : InstallResult()
        data class Failed(val cause: Throwable) : InstallResult()
    }

    fun ensureInstalled(claudeDir: Path): InstallResult { ... }
}
```

**Install policy: write-if-missing, never overwrite.** Simplest YAGNI-correct rule. Users wanting the newer prompt that ships with a plugin update delete the file and the next refresh reinstalls it. Documented in `docs/user-guide.md`. A future `managed-by: clawdea` frontmatter marker would enable auto-update with user opt-out, but is explicitly out of scope for v1.

**Invocation hooks** — two places, both idempotent:

1. **`PrimerService.refreshAndGet()`** — first thing after the `enableKnowledgeLayer` check, if `enableWikiLibrarian` is on. Cost is one `Files.exists` per refresh.
2. **`/seed-wiki` `BridgeExpandingHandler`** — extend its handler to write the agent file before forwarding the prompt to the main agent.

**Dogfood case** — commit `.claude/agents/wiki-librarian.md` into the ClawDEA repo itself. **Sync mechanism for v1: manual** — the resource file at `src/main/resources/agents/wiki-librarian.md` is the canonical text; the committed `.claude/agents/wiki-librarian.md` is regenerated by deleting it and re-running ClawDEA (which triggers `WikiLibrarianInstaller.ensureInstalled`). PR reviewers see agent-prompt changes in both files' diffs. A build-time copy step is deferred to v2 if drift between the two files becomes a recurring issue.

## Component 7 — settings + opt-out

Add to `ClawDEASettings.State`:

```kotlin
var enableWikiLibrarian: Boolean = true
```

When `false`:

- `WikiLibrarianInstaller.ensureInstalled` is not invoked (no file write).
- `WikiIndexSource.buildDirective()` returns `buildLegacyDirective()` (the verbatim current wording, kept as a private method on the same companion object).
- `McpWikiTools.registerAll()` re-registers `search_wiki` per Component 5.

Default is `true`. The setting exists as a safety valve (bisecting regressions, contexts where `Task` invocations are unexpectedly expensive) but the v1 expectation is: install ClawDEA → librarian is on → wiki feels different immediately.

## Testing

Pure-Kotlin unit tests, run by `./gradlew test`, following `<ClassName>Test.kt`. Nothing requires `LightJavaCodeInsightFixtureTestCase` — all tests are headless-safe.

**New tests:**

- `DriftEventTest` (or extend) — `WikiSuggestion.signature` correctness: stable across runs, ignores `index.md` for primary target, differs across kinds, identical for same kind + primary target.
- `WikiSuggestionWriterTest` — validation rules from Component 2; load-modify-write semantics (append, update on signature match, no-op on dismissed); atomic write.
- `DriftAutoApplierTest` (extend) — `WikiSuggestion` branch always returns event to `remaining`; existing `CodeRename` / `ManifestStale` behaviour unchanged.
- `DriftStateStoreTest` (extend) — v1 file reads with empty suggestions; v2 file round-trips suggestions; `dismiss()` strips from `suggestions` and adds to `dismissed`.
- `WikiIndexSourceTest` (extend) — librarian directive contains `"Task"`, `"wiki-librarian"`, `"read_wiki_page"`; lacks `"search_wiki"`. Legacy directive preserved verbatim. Toggle behaviour via `enableWikiLibrarian`.
- `McpWikiToolsTest` (extend) — librarian-on: `read_wiki_page` + `record_wiki_suggestion` registered, `search_wiki` not. Librarian-off: all three registered. Required-args list for `record_wiki_suggestion` matches spec.
- `WikiLibrarianInstallerTest` — writes when missing (content equals resource bytes); no-op when present; returns `Failed` on permission error. Tmpfs paths.
- `DriftDetectionServiceTest` (extend or new) — `runOnce()` includes `state.suggestions` in event stream; entries survive round-trip.

**Tests explicitly not in scope:**

- Wiki-librarian behavioural output (LLM, not a deterministic function). The contract is tested (file written, allowlist correct, directive correct, MCP tool registered); output quality is empirical.
- Live `Task` invocation in the main CLI. `CliEventParserTest` already covers `ToolUse` parsing, which is what `Task` produces.

**Manual smoke checklist for the implementer (not committed):**

1. Open ClawDEA in a fresh project → `.claude/agents/wiki-librarian.md` is auto-installed.
2. Ask a covered question → `Task(wiki-librarian, ...)` is invoked; answer cites the right page.
3. Ask an uncovered question → `record_wiki_suggestion` fires; entry appears in `.drift-state.json`.
4. Run `/refresh-wiki` → suggestion shows in the drain output.
5. Set `enableWikiLibrarian=false` → legacy directive returns; `search_wiki` callable.
6. Dismiss a suggestion → entry gone from `state.suggestions`, signature in `state.dismissed`; re-recording the same suggestion silently drops.

## Open implementation details

- **Dream signature alignment (follow-up, not blocking).** `DreamEventMapper` builds signatures like `dream-missing-concept:<key>` where the key encodes targets+action+cost+evidence. The librarian's `WikiSuggestion` uses `wiki-suggestion:<kind>:<primaryTarget>` and will not dedup against Dream's events. v1 acceptance: two separate UI entries if both fire on the same gap. v2 follow-up: introduce a shared signature builder or a normalisation step at queue-read time.
## Out of scope / future work

- **Distributed wiki storage.** Documents committed across the source tree, indexed at scale. Spec deferred until v1 of the librarian is validated in practice.
- **`wiki-author` subagent.** Would consume the suggestion queue and produce wiki edits, enabling silent application under `autoUpdateWiki=true`. Cleanly slots into the suggestion queue without changes to v1's persistence.
- **Permission-judge subagent.** Stateless one-shot gating tool approvals via `McpPermissionPromptTool`.
- **Adversarial code reviewer subagent.** Stateless one-shot reviewing `propose_edit` payloads before the diff dialog.
- **Managed-file marker.** `managed-by: clawdea` frontmatter to enable auto-update of the agent file with user opt-out.
- **"Draft this suggestion" UI button.** Wire the notification UI's per-suggestion entry to invoke the future `wiki-author` subagent on demand.
