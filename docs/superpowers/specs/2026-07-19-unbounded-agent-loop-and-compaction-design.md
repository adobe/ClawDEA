# Unbounded agent loop & context compaction (OpenAI-compatible backend)

**Date:** 2026-07-19
**Status:** Design — approved for planning
**Scope:** `AgentLoopController`, `OpenAiCompatibleAgentBackend`, new `ConversationCompactor`, `ClawDEASettings`

## Problem

The OpenAI-compatible agent backend runs its own in-process agentic loop
(`AgentLoopController`) because the OpenAI chat-completions API is stateless — unlike the
Claude CLI / Codex backends, there is no external harness that owns loop termination. That
loop enforces three hardcoded limits (`OpenAiCompatibleAgentBackend.kt:432-434`):

- `maxToolRounds = 10` — fatal `isError=true` result when exceeded (`AgentLoopController.kt:296`)
- `maxElapsedMs = 600_000` (10 min) — fatal (`AgentLoopController.kt:119`)
- `maxContextChars = 1_000_000` — fatal "Context budget exceeded" (`AgentLoopController.kt:137`)

These are too restrictive for long autonomous sessions. A user may legitimately expect the
agent to run for hours across hundreds of tool calls. The context limit in particular should
not be fatal — the conversation should **compact** and continue.

No compaction exists on this backend today. The `/compact` slash command is a Claude CLI
built-in forwarded to the subprocess; it does not touch this in-process loop.

## Goals

1. Round and time ceilings become **configurable soft limits**; default `0 = unlimited`.
   A non-zero value triggers an indefinite-wait "continue?" checkpoint instead of erroring.
2. Context is **never fatal** — it triggers LLM summarization compaction and the loop continues.
3. Preserve the OpenAI message-structure invariant (every assistant `tool_calls` message is
   followed by its `tool` results) across compaction.

## Non-goals (YAGNI)

- Tool-result eviction tier (summarize-all is enough for v1).
- Per-model compaction tuning.
- Any change to the Claude / Codex backends — they have their own harness.

## Decisions (from brainstorming)

- **Control model:** hybrid (C). Rounds/time are configurable soft limits that checkpoint;
  context auto-compacts. Default ceilings are `0` = no ceiling.
- **Compaction trigger (C):** token-based against the model's real context window when known;
  char-based fallback (reusing today's char-sum) when the window is unknown.
- **Compaction mechanism (A):** LLM summarization — one separate completion call replaces old
  history with `[system] + [summary] + [recent verbatim tail]`.
- **Boundary safety (B):** summarize *all* prior history and keep only a verbatim tail chosen to
  begin at a clean boundary. Sidesteps dangling tool_call/result pairs entirely and makes ledger
  reconciliation trivial (summarized ids are simply evicted).
- **Checkpoint UX (B):** a dedicated indefinite-wait checkpoint. Reuses the backend's existing
  blocking ask-user pattern (`promptPartialRetry`, `OpenAiCompatibleAgentBackend.kt:473`), **not**
  the permission-prompt path (which has a 60s auto-resolve cap that fights the multi-hour use case).
  Escape still aborts.

## Configuration

New `ClawDEASettings` (application-level) fields, surfaced in the ClawDEA settings panel near the
OpenAI-compatible provider config:

| Setting | Default | Meaning |
|---|---|---|
| `agentMaxToolRounds` | `0` | 0 = unlimited. Non-zero = checkpoint every N rounds. |
| `agentMaxElapsedMinutes` | `0` | 0 = unlimited. Non-zero = checkpoint after M minutes. |
| `agentContextCompactionThreshold` | `0.8` | Fraction of the context budget at which to compact. |

The char-based fallback budget (used only when the model's context window is unknown) is a code
constant, not a setting: `CHAR_FALLBACK` retains today's `maxContextChars` value (`1_000_000`).
The threshold applies to whichever budget is in effect — `budget * threshold` for tokens,
`CHAR_FALLBACK * threshold` for chars.

`OpenAiCompatibleAgentBackend` passes raw settings through; `AgentLoopController` owns the
`0 = unlimited` interpretation, in exactly one place:

```kotlin
if (maxToolRounds > 0 && toolRounds >= maxToolRounds) { checkpoint(SoftLimitReason.Rounds, toolRounds) }
if (maxElapsedMs > 0 && elapsed > maxElapsedMs)       { checkpoint(SoftLimitReason.Time, elapsedMin) }
```

Because a checkpoint resets the counter and continues, `toolRounds` keeps climbing and the guard
fires at each multiple of N (N, 2N, 3N…). Existing tests that pass `maxToolRounds = 3` keep a
non-zero limit; their semantics change from "error" to "checkpoint".

## Soft-limit checkpoint

`TurnState.Paused` is UI-only ("Pause is not observed by the CLI", `TurnStateMachine.kt:14`), so
the checkpoint must block the backend's loop coroutine. `AgentLoopController` stays pure of UI and
receives an injected callback (same pattern as retry/permission prompts):

```kotlin
private val onSoftLimit: suspend (SoftLimitReason, count: Int) -> Boolean = { _, _ -> true }
```

Flow:

1. Non-zero ceiling trips → loop calls `onSoftLimit(reason, count)` and suspends.
2. `true` → reset the relevant counter, continue for another N rounds / M minutes.
3. `false` → emit a clean terminal `Result` with **`isError = false`** (user chose to stop a
   healthy run — distinct from today's `isError = true` limit) and end the turn.
4. Backend implementation renders a Continue / Stop checkpoint banner in chat and waits
   **indefinitely** (no 60s cap). Escape during the wait uses the existing abort path.

Cost/session accounting keys off `isError`, so the `false`-at-checkpoint case is accounted as a
successful (user-stopped) turn.

## Compaction mechanism

Checked at the top of the loop where the fatal context error is today (`AgentLoopController.kt:137`).

Budget measurement:

```kotlin
val budget: Int? = contextWindowFor(modelId)             // profile / ModelCatalogProbe; null if unknown
val used: Int = state.usage.inputTokens + state.usage.outputTokens
val overThreshold = when {
    budget != null -> used >= budget * threshold
    else           -> contextChars >= CHAR_FALLBACK * threshold   // reuse state.messages char-sum
}
```

`state.usage.inputTokens` is already populated from the API `usage` block each round, so the token
path needs only the window-size lookup.

When `overThreshold`:

1. **Split** `state.messages` into `[system prefix]` + middle + candidate tail.
2. **Tail selection:** the most recent messages beginning at a clean boundary (a `user` message,
   or an `assistant` message with no pending `tool_calls`) — never mid tool_call/result pair. If no
   clean boundary exists in the target window, shrink the tail until one does (worst case: empty).
3. **Summarize** the middle span with one separate, non-streamed completion call ("Summarize the
   conversation so far, preserving the task goal, decisions, and findings"). Internal — not
   rendered as assistant output.
4. **Rebuild:** `state.messages = [system] + [summary message] + [verbatim tail]`.
5. **Reconcile ledger:** clear from `state.completedToolCallIds` every id in the summarized span.
   Those calls are now prose and will never be re-issued.
6. **Emit a chat notice** ("Compacted context — summarized N earlier messages") and continue the
   same round with no user interaction.

Failure handling: if the summarization call itself fails, do **not** send an over-budget request
(the provider would 400). Fall back to today's terminal "context budget exceeded" result for that
round. Rare; degrades to current behavior.

Boundary-safety guarantee: because we summarize all prior and keep only a clean-start tail, we can
never leave a dangling `tool_calls`-without-results pair.

## Components

### New: `ConversationCompactor` (pure, injectable)

```kotlin
class ConversationCompactor(
    private val summarize: suspend (List<AgentMessage>) -> String,   // one completion call, injected
) {
    suspend fun compact(messages: List<AgentMessage>, keepTailTarget: Int): CompactionResult
}
data class CompactionResult(val messages: List<AgentMessage>, val evictedToolCallIds: Set<String>)
```

Kept pure of IntelliJ/coroutine-scope concerns (same discipline as `AgentLoopController`,
`:70-72`). Houses the boundary-safety / tail-selection / rebuild logic, unit-testable with a fake
`summarize`.

### Changed: `AgentLoopController`

- `maxToolRounds` / `maxElapsedMs` gain "0 = skip" guards.
- Injected `onSoftLimit` callback; the three top-of-loop checks change from "emit error + return"
  to "checkpoint" (rounds/time) or "compact" (context).
- Injected `ConversationCompactor`, budget threshold, and `contextWindowFor(modelId)`.

### Changed: `OpenAiCompatibleAgentBackend`

- Reads the three settings; passes them through.
- Wires `onSoftLimit` to a blocking Continue/Stop checkpoint prompt (reusing the
  `promptPartialRetry` blocking pattern, `:473`).
- Provides the real `summarize` closure (a non-streamed completion) and `contextWindowFor`.

## Testing

| Unit | Tests |
|---|---|
| `ConversationCompactor` | summarize-all + clean tail; never splits a tool_call/result pair; evicted ids returned; empty-tail worst case; summarize-failure surfaces as error (no lossy send) |
| `AgentLoopController` | `0` rounds → never checkpoints (runs to natural end); non-zero → `onSoftLimit` fires at N and 2N; `onSoftLimit → false` ends turn with `isError=false`; over-threshold → compaction then continues same round; existing `maxToolRounds=3` test adapted to checkpoint semantics |
| `OpenAiCompatibleAgentBackend` | settings → controller wiring; checkpoint blocks then continues/stops; compaction notice emitted |

## Risks

- **Char fallback imprecision:** char→token ratio varies by tokenizer. The `0.8` threshold plus a
  conservative `CHAR_FALLBACK` mitigates over-budget sends; the summarize-failure fallback is the
  final backstop.
- **Summary quality is lossy:** inherent to approach A. Keeping a verbatim recent tail preserves
  the immediate working set; the summary preserves goal/decisions/findings.
- **Runaway with all ceilings at 0:** by design the only automatic stop is "model stops calling
  tools"; Escape is the manual stop. Users who want a safety net set a non-zero ceiling.
