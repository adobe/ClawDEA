# CLI bridge

**Purpose** Spawn and manage the `claude` CLI as a subprocess, parse its NDJSON event stream into typed `CliEvent`s, and own the lifecycle for restart, pause (SIGINT), and abort.

## Invariants

- The CLI is invoked with `-p --output-format stream-json --input-format stream-json --verbose --include-partial-messages --exclude-dynamic-system-prompt-sections`. The `stream-json` format is the contract — `CliEventParser` parses NDJSON line-by-line and any other output format breaks event shape detection ([CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)).
- `--include-hook-events` is intentionally **not** passed: ClawDEA is the harness, so user-configured Claude Code hooks would duplicate or conflict with first-class lifecycle UI (edit review, permission, session). `CliProcessHookEventsOmissionTest` is the regression ([CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)).
- The system prompt is written to a **temp file** and passed via `--append-system-prompt-file`, never inline. With skill catalog + primer it routinely exceeds 32 KB, which would blow past Windows' `CreateProcess` 32,767-char command-line limit ([CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)).
- Permission settings JSON is also written to a temp file and passed via `--settings`, never inline. On Windows the entry point is `claude.cmd`, so ProcessBuilder routes args through `cmd.exe` which strips inner quotes and splits on unquoted spaces — inline JSON gets corrupted ([CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)).
- A reader job is generation-scoped: `expectedExitGeneration` and `activeGeneration` ensure a stale reader from a previous CLI process can never emit events that look like a crash after a restart ([CliBridge.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliBridge.kt)).
- `CliEventParser` parses NDJSON manually (no Gson) for high-volume stream throughput. The sealed `CliEvent` hierarchy is the only wire-shape contract ([CliEvent.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliEvent.kt)).
- `CliEvent.Result.contextTokens` is **per-turn** (input + cache_read + cache_creation from `result.usage`), not a cumulative running total. `EventStreamHandler` overwrites `totalTokensUsed = event.contextTokens` on each Result rather than adding — summing across turns would double-count cache reads ([CliEventParser.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliEventParser.kt), [EventStreamHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/EventStreamHandler.kt)).
- `CliEvent.Result.contextWindow` is read from `result.modelUsage.<model>.contextWindow` and is the **authoritative denominator** for the context-budget indicator. Falling back to a hardcoded 200K (the previous behavior, fixed in 861cf1e) underreports headroom for Opus 4.7 (1M) by 5x ([CliEventParser.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliEventParser.kt), [ChatPanel.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatPanel.kt)).
- The per-message `stream_event.message_delta.usage` block (emitted with `--include-partial-messages`) is intentionally **not** parsed into a CliEvent — it represents that single message's tokens and must not be summed with `result.usage`. Only the `result` event's usage is consumed for the budget label; intermediate `message_delta` events are dropped to `CliEvent.Unknown` by design ([CliFixtureReplayTest.kt](../../../src/test/kotlin/com/adobe/clawdea/cli/CliFixtureReplayTest.kt)).

## Resolution pipeline

1. `CliBridge.start()` increments `activeGeneration`, resolves the resume session id from `ChatAutoResumeState`, and calls `CliProcess.start()`.
2. `CliProcess.start()` resolves the `claude` binary (handling Finder/Dock launches with no shell PATH), runs preflight (auth check, binary exists), then assembles the command:
   - base flags (stream-json, verbose, partial messages)
   - permission flags + settings file (only when MCP is up)
   - `--mcp-config <temp file>` pointing at the local `McpServer` port
   - **No `--agents` injection.** The in-chat wiki-librarian is reached via the `ask_wiki_librarian` MCP tool (`McpWikiTools`) on every chat backend, and the wiki-author runs out-of-band via `WikiAuthorInvoker`'s own dedicated `claude -p` subprocess. Injecting the ~14KB agents JSON into the interactive CLI's argv used to blow past Windows' `cmd.exe` 8191-char command-line cap (`refactor(wiki): remove dead LibrarianMode + in-chat agents JSON builder`, commit 9f11b41f) ([CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)).
   - `--disallowedTools` (built-in tools we want Claude to use the MCP variant of)
   - `--append-system-prompt-file <temp file>` (MCP system prompt + edit-review + skill catalog + primer)
   - model + effort args, optional `--resume <session-id>`
3. `CliProcess` spawns the process in `workingDirectory`, wires three streams (stdin/stdout/stderr) and a stderr drain thread.
4. `CliBridge` launches a coroutine reader on `Dispatchers.IO` that loops `cliProcess.readLine()` until the process exits, parses each line through `CliEventParser`, and emits into a `MutableSharedFlow<CliEvent>`.
5. On `CliEvent.AuthFailure` it invokes `onAuthFailure`. On `CliEvent.Result` it captures `sessionId` for resume.
6. Pause = `process.toHandle().destroy()` (SIGINT). Abort = `destroyForcibly()`. Restart calls `stop()` then `start()`, bumping the generation so the old reader's tail emissions are ignored.

## System-prompt assembly

The `--append-system-prompt-file` temp file is composed in `CliProcess` from several sources concatenated in a fixed order: `WIKI_LIBRARIAN_TOOL_PROMPT` (only when `enableWikiLibrarian`) → `MCP_SYSTEM_PROMPT` → `EDIT_REVIEW_PROMPT` → baseline defaults → skill catalog → primer ([CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)).

- **There is no longer a subagent-mode branch here.** Since `refactor(wiki): remove dead LibrarianMode + in-chat agents JSON builder` (commit 9f11b41f), the interactive chat CLI always appends the single `WIKI_LIBRARIAN_TOOL_PROMPT` block (which names the `ask_wiki_librarian` MCP tool) when the librarian is enabled — there is no `--agents` subagent path for the interactive CLI to choose between, so `chooseLibrarianMode`/`LibrarianMode` (which used to make that choice) were deleted. The now-unused sibling constant `WIKI_LIBRARIAN_PROMPT` (the subagent-persona text) still exists in `CliProcess` but nothing references it.
- `WIKI_LIBRARIAN_TOOL_PROMPT`, `MCP_SYSTEM_PROMPT`, `EDIT_REVIEW_PROMPT` are `private`/`internal val` companion constants loaded via `loadStaticPrompt(name)`, which pulls `src/main/resources/prompts/<name>.md` through `PromptResource.load(...).trim()` and logs at error level if the resource is missing (a packaging defect, not a runtime condition) — they are **not** inline triple-quoted string literals. `PromptResource` is a process-lifetime `ConcurrentHashMap` cache; loaded once per process, not per turn.
- Newer prompt blocks follow the same **extraction pattern**: the text lives under `src/main/resources/prompts/<name>.md` and is pulled at assembly time via `PromptResource.load("<name>")` ([PromptResource.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/prompts/PromptResource.kt)). A missing resource throws `IllegalArgumentException` from the raw loader. The baseline-defaults block is the **fail-soft** variant of this pattern — `buildBaselineDefaultsPrompt(enabled)` loads `baseline-defaults` and degrades to `""` ("feature off") rather than crashing the turn on a missing resource, unlike the always-on core blocks above which log an error but still degrade to `""` rather than aborting CLI startup.
- `WikiAgentsArg` still exists but is **no longer used to build a `--agents` argument for the interactive chat CLI.** It now only supplies (a) `librarianPromptBody()` / `authorPromptBody()` — the fully-substituted agent prompt bodies reused as system prompts for the out-of-band librarian/author subprocesses (`ClaudeSubprocessLibrarian`, `CodexExecLibrarian`, `McpWikiTools.askWikiLibrarian`'s agentic path, `WikiAuthorInvoker`'s `buildAuthorOnlyJson()` for its own dedicated `claude -p` process), and (b) the `{{placeholder}}` substitution helper `substituteTemplates`, which rewrites tokens like `{{wiki-page-invariant}}` / `{{wiki-page-navigation}}` via `PromptResource.load(<token>)` (regex `\{\{([a-z0-9-]+)\}\}`) ([WikiAgentsArg.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiAgentsArg.kt)).

A refactor that extracts the three inline constants to resources must follow this seam: resource at `prompts/<name>.md`, load through `PromptResource`, and decide fail-soft (degrade to `""`, like baseline-defaults) vs. fail-hard (throw, surfacing a packaging bug) per block. Test contracts to mirror: `CliProcessBaselineDefaultsTest` asserts the resource loads, contains its keyword markers, and that the `enabled`/disabled helper returns the block or `""`; `PromptResourceTest` covers load, cache identity (`assertSame`), and the missing-resource throw ([CliProcessBaselineDefaultsTest.kt](../../../src/test/kotlin/com/adobe/clawdea/cli/CliProcessBaselineDefaultsTest.kt), [PromptResourceTest.kt](../../../src/test/kotlin/com/adobe/clawdea/knowledge/prompts/PromptResourceTest.kt)).

## Anti-patterns

- **Inline system prompt or settings JSON** — Will silently break on Windows or for prompts >32 KB. Always write to a temp file marked `deleteOnExit()` and pass via `--*-file` / `--settings <path>`.
- **Adding `--include-hook-events`** — Will produce event shapes `CliEventParser` does not model and will collide with edit-review and permission UIs. There is a drift watcher entry (#94) flagging upstream changes to hook semantics.
- **Using Gson in `CliEventParser`** — The manual parser is a deliberate perf optimization for high-volume `--include-partial-messages` streams. Switching to Gson regressed allocation hotspots in past profiles.
- **Reading process output without checking the reader generation** — A late-arriving line from a previous process will be parsed and emitted as a fresh event, producing phantom crashes or duplicate Results. Always gate emission on `isCurrentReader(readerGeneration)`.
- **Summing `message_delta.usage` across messages, or adding it to `result.usage`** — Both shapes report tokens for their own scope (one message vs end-of-turn). Adding them double-counts. The budget indicator must read only `result.usage`.
- **Hardcoding the context-window denominator** — The window is model-dependent (Sonnet/Opus default 200K, Opus 4.7 1M, future variants unknown). Use `event.contextWindow` from the Result and fall back to `DEFAULT_CONTEXT_WINDOW_TOKENS` only when CC didn't report one. Regression fixed in commit 861cf1e (PR #73).

## Source pointers

- [CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt) — process spawn, command assembly, temp-file handling, CLI binary resolution
- [CliBridge.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliBridge.kt) — coroutine reader, generation tracking, `SharedFlow<CliEvent>` fan-out
- [CliEvent.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliEvent.kt) — sealed event hierarchy (SystemInit, TextDelta, ReasoningDelta, AssistantMessage, ToolUse/Result, Result, BackgroundTask, AuthFailure, GoalFeedback, TaskEvent)
- [CliEventParser.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliEventParser.kt) — manual NDJSON parser
- [CliEnvironment.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliEnvironment.kt) — env-var injection (auth, locale, telemetry opt-out)
- [TaskEventExtractor.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/TaskEventExtractor.kt) — TaskWidget event extraction from partial-message stream
