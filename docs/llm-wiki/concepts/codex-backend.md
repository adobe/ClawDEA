# Codex backend

**Purpose** Run the OpenAI **`codex` CLI** as an alternate agentic backend behind the same `CliBridge` / ChatPanel path as Claude, so an OpenAI provider drives a full ClawDEA session (streaming, steering, approvals, cost, resume) without the UI knowing which CLI is behind it.

The backend is selected once per bridge from the **effective provider**: `CliBridge.isCodexProvider()` returns true for `openai` and `openai-subscription`, and the bridge constructs a `CodexAppServerProcess` + `CodexAppServerParser` pair instead of the Claude `CliProcess` + `CliEventParser`. A provider switch requires a bridge restart (`ChatSession` recreates it), which re-runs the selection ([CliBridge.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliBridge.kt)).

## Invariants

- **The backend is a single long-lived `codex app-server` process, not one-shot `exec`.** `CodexAppServerProcess` spawns `codex app-server --stdio` once and speaks JSON-RPC 2.0 over stdio (newline-delimited). One process spans the whole session; this is what enables per-token streaming (`item/agentMessage/delta`), a native interrupt (`turn/interrupt`), native mid-turn steering (`turn/steer`), and real credits — none of which the earlier `codex exec` facade could provide ([CodexAppServerProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CodexAppServerProcess.kt)).
- **Standing instructions live on the thread, not on every turn.** Codex has no system-prompt flag, so `CodexInstructions.build()` (tooling prompt + skill catalog + project primer) is passed once as `baseInstructions` on `thread/start`. Per turn, only the raw user text is sent via `turn/start` — do not re-prepend the preamble ([CodexInstructions.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CodexInstructions.kt)).
- **Approvals must run off the stdout reader thread.** `CodexApprovalGate` blocks on the user; the reader thread must keep pumping so the `item/started` that renders the tool block the approval card attaches to actually arrives. `CodexAppServerProcess` dispatches gate decisions on a daemon `approvalExecutor` for exactly this reason ([CodexAppServerProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CodexAppServerProcess.kt)).
- **The approval executor and out-queue are recreated on every `start()`.** `stop()` calls `shutdownNow()` / enqueues a STOP sentinel; a `stop()→start()` cycle (model switch, `/resume`, wake-recovery) would otherwise submit approvals to a dead executor (`RejectedExecutionException` → silently dropped reply → codex hangs → IDE freeze), or let a stale STOP unblock the next session's reader before any turn runs ([CodexAppServerProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CodexAppServerProcess.kt)).
- **`account/rateLimits/*` is consumed by the process, never forwarded to the parser.** Credit/rate-limit snapshots feed the Cost Control gauge (`CostTracker.updateOpenAiUsage`) directly; the chat stream must not see them. Everything else with a `method` and no `id` is a notification forwarded to `CodexAppServerParser` ([CodexAppServerProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CodexAppServerProcess.kt)).
- **`turn/steer` is only valid while a turn is genuinely in flight.** The process gates `steer()` on `turnActive` (true between a turn's `turn/start`/`turn/steer` ack and its terminal `turn/completed`/`error`). When not steerable, `steer()` returns false and `CliBridge` falls back to a fresh `turn/start` ([CodexAppServerProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CodexAppServerProcess.kt)).
- **ChatGPT accounts reject generic API model IDs.** The OpenAI-subscription model catalog is read from codex's own `~/.codex/models_cache.json` (only `visibility == "list"` entries), never a static list — a ChatGPT account 400s on `gpt-5`/`gpt-5-codex`, so codex's per-account resolved list is the only reliable source ([CodexModelProbe.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/CodexModelProbe.kt)).
- **The Codex parser never sets `parentToolUseId`, so subagent card grouping does not work on this backend.** `CodexAppServerParser` builds every `AssistantMessage` (`assistantText` / `toolUse`) and `ToolResult` without a `parentToolUseId` — it defaults to `null` ([CodexAppServerParser.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CodexAppServerParser.kt), [CliEvent.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliEvent.kt) lines 40, 59). Depth-1 subagent grouping ([subagents](subagents.md)) is driven entirely by that field, so `SubAgentController.parentCardFor` always returns `null` and no `.subagent-block` card is ever created; `item/started` also has no `Agent`→tool_use mapping (only `commandExecution`→`Bash`, `mcpToolCall`, `fileChange`), so a subagent dispatched under codex renders its inner steps flat in the main chat. A "inner steps leak to main chat" bug is expected behavior on the Codex backend, not a regression.
- **Codex sign-in has no paste-code step.** Unlike Claude's `SubscriptionAuth`, `CodexSubscriptionAuth.signIn()` just streams `codex login` and lets codex open the browser / device-auth itself. Status comes from `codex login status` plain text (no `--json`, no email/tier), parsed **negative-case-first** because "not logged in" contains "logged in" ([CodexSubscriptionAuth.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/CodexSubscriptionAuth.kt), [CodexSubscriptionAuthProbe.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/CodexSubscriptionAuthProbe.kt)).

## Handshake and turn flow

Driven asynchronously on the reader thread off each response:

1. `start()` spawns `codex app-server --stdio` and sends `initialize` (with `clientInfo`).
2. On the `initialize` response → send the `initialized` notification, then `thread/start` (or `thread/resume` if resuming) carrying `baseInstructions`, `approvalPolicy=on-request`, `sandbox=workspace-write`, and ClawDEA's local MCP server URL in `config.mcp_servers`.
3. On the thread response → capture the `threadId`, flush any `pendingPrompt`, and pull the current credit balance via `account/rateLimits/read` (only with a project).
4. `writeLine()` (a Claude-format user message from `CliBridge.sendMessage`) → one `turn/start` with the raw user text plus resolved `model` and mapped `effort`. If the thread isn't ready yet, the prompt is stashed as `pendingPrompt`.
5. Server → client **requests** (shell/patch approvals) are routed through `CodexApprovalGate` on a worker thread; elicitation / user-input requests are accepted inline. Server → client **notifications** go to `CodexAppServerParser`.
6. `thread/resume` failure (foreign/expired id) falls back to a fresh `thread/start` so the user still gets an answer (the replayed transcript, if any, is already prepended by `CliBridge`).

## Notification → CliEvent mapping

`CodexAppServerParser` switches on the JSON-RPC `method` and normalizes to the shared `CliEvent` hierarchy ([CodexAppServerParser.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CodexAppServerParser.kt)):

- `thread/started` → `SystemInit` (session id for resume)
- `item/agentMessage/delta` → `TextDelta` (per-token stream)
- `item/reasoning/textDelta` + `summaryTextDelta` → `ReasoningDelta` (thinking stream; no Claude analogue)
- `item/started` → `AssistantMessage` with a tool use (`commandExecution`→`Bash`, `mcpToolCall`→`mcp__server__tool`, `fileChange`→`apply_patch`)
- `item/completed` → whole `AssistantMessage` (agent text) or `ToolResult` (tool outcome)
- `thread/tokenUsage/updated` → stashed, applied to the next `Result`
- `turn/completed` → `Result` (carries the stashed per-turn token usage)
- `error` → `AuthFailure` (if it looks like an auth error) or an error `Result`

Anything else is a deliberately-ignored `Unknown` with a **blank** `rawJson` so the ChatPanel Unknown branch early-returns. The `modelId` is stamped onto every `AssistantMessage` (the stream doesn't echo the model) so the cost footer can label the turn.

**Limitation: `parentToolUseId` is not populated.** Every `AssistantMessage` and `ToolResult` from the parser defaults `parentToolUseId` to `null`, which means subagent card grouping (as described in [[subagents]]) **does not work on the Codex backend**. Subagent inner tool events leak into the main chat as top-level events. This is a Claude-backend-only feature — the codex notification stream carries no metadata linking a tool use to a parent subagent dispatch, and the parser has nowhere to store such a link even if the server provided one. Subagent card collapsing and step-count tracking rely on `parentCardFor` in `SubAgentController`, which requires `parentToolUseId` to be set on every child event. Until codex's app-server protocol supports parent tracking or ClawDEA wraps codex's Agent call with synthetic metadata, this remains out of scope.

## Approvals

`CodexApprovalGate` routes codex's own shell/patch approval requests through the *same* permission gate the Claude backend uses (`PermissionRouterRegistry` → `PermissionDispatcher`), so they honor "Tool approval" mode and "Auto-accept edits". Decision order mirrors `McpPermissionPromptTool`: `allow-all` → silent allow; Claude settings policy (`.claude/settings*.json`) → allow/deny; otherwise an interactive card that blocks until the user decides. File patches short-circuit to allow when "Auto-accept edits" is on. When **no** panel router claims the call, the gate **falls back to approve** (non-regression vs. the pre-gate auto-approve; the `workspace-write` sandbox still applies). Unlike the Claude HTTP MCP path (hard ~60s cap), the app-server waits indefinitely on the reply, so the card gets a generous 10-minute window ([CodexApprovalGate.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CodexApprovalGate.kt)).

## Cost and sessions

- `CodexRateLimitMapper` maps a codex `RateLimitSnapshot` (`individualLimit` spend gauge, or a `credits.balance` fallback, plus `primary`/`secondary` rate-limit windows) onto ClawDEA's `SubscriptionUsage`, giving the OpenAI subscription the same live Cost Control gauge Claude has ([CodexRateLimitMapper.kt](../../../src/main/kotlin/com/adobe/clawdea/cost/CodexRateLimitMapper.kt)).
- `CodexSessionScanner` scans codex rollout JSONL files under the **global** `~/.codex/sessions/YYYY/MM/DD/` tree, filtering by the `cwd` recorded in each rollout's `session_meta`, so codex sessions appear in the resume picker and their transcripts replay across backends. It strips codex/ClawDEA-injected synthetic turns — the leading `<recommended_plugins>` and `<environment_context>` XML blocks (both listed in `SYNTHETIC_USER_BLOCKS`), the JetBrains harness note, and everything before the last `User request:` marker ([CodexSessionScanner.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/session/CodexSessionScanner.kt)).

## Anti-patterns

- **Re-prepending `CodexInstructions` on every turn** — the preamble is standing thread state (`baseInstructions`); per-turn only the raw user text is sent.
- **Blocking the stdout reader thread on an approval** — deadlocks the very `item/started` the card needs. Always dispatch gate decisions on the approval executor.
- **Reusing the approval executor / out-queue across a `stop()→start()` cycle** — recreate them in `start()`; a shut-down executor silently drops the approval reply and hangs codex.
- **Forwarding `account/rateLimits/*` to the parser** — those feed `CostTracker` directly, not the chat stream.
- **Populating the OpenAI-subscription catalog from a static model list** — read codex's `models_cache.json` (`visibility == "list"` only); ChatGPT accounts reject generic API IDs.
- **Watching stdin for a paste-code prompt on codex login** — codex opens the browser itself; there is no stdin paste step (that's the Claude flow).

## Source pointers

- [AgentProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/AgentProcess.kt) — the backend-agnostic contract `CliBridge` drives (`start`/`readLine`/`writeLine`/`sendInterrupt`/`stop`/`supportsSteer`/`steer`)
- [CodexAppServerProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CodexAppServerProcess.kt) — `codex app-server` lifecycle, JSON-RPC framing, handshake, steer/interrupt, rate-limit consumption
- [CodexAppServerParser.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CodexAppServerParser.kt) — notification-stream → `CliEvent` normalization
- [CodexApprovalGate.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CodexApprovalGate.kt) — shell/patch approvals through the shared permission gate
- [CodexInstructions.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CodexInstructions.kt) — first-turn `baseInstructions` preamble (tooling + skills + primer)
- [CodexModelProbe.kt](../../../src/main/kotlin/com/adobe/clawdea/gateway/CodexModelProbe.kt) — OpenAI-subscription model catalog from `models_cache.json`
- [CodexRateLimitMapper.kt](../../../src/main/kotlin/com/adobe/clawdea/cost/CodexRateLimitMapper.kt) — `RateLimitSnapshot` → `SubscriptionUsage` for the Cost Control gauge
- [CodexSessionScanner.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/session/CodexSessionScanner.kt) — codex rollout scan + transcript replay for resume
- [CodexSubscriptionAuth.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/CodexSubscriptionAuth.kt) — ChatGPT sign-in/out driver (`codex login`/`logout`)
- [CodexSubscriptionAuthProbe.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/CodexSubscriptionAuthProbe.kt) — `codex login status` text probe (negative-case-first)
- [CodexSubscriptionAuthEvent.kt](../../../src/main/kotlin/com/adobe/clawdea/auth/CodexSubscriptionAuthEvent.kt) — distinct auth-event topic so the Claude card doesn't react to codex sign-in

## Related

- [CLI bridge](cli-bridge.md) — the reader loop and system-prompt assembly that both backends share
- [Authentication](authentication.md) — effective-provider resolution that selects this backend
- [Cost tracking and session model](cost-tracking.md) — per-turn spend accounting the rate-limit mapper feeds
- [Turn state machine](turn-state-machine.md) — Idle/Streaming/Paused, and the steer/interrupt path
- [Subagents](subagents.md) — card grouping keys on `parentToolUseId`, which this backend's parser never sets, so subagent inner steps render flat in the main chat

## Reaching the wiki librarian from a Codex chat

A Codex chat has no `--agents` / subagent mechanism, so it cannot spawn `Agent(subagent_type="wiki-librarian")`. Its only path to the librarian is the `ask_wiki_librarian` MCP tool, which `CodexInstructions.build` advertises via `CliProcess.WIKI_LIBRARIAN_TOOL_PROMPT`. The tool's handler (`McpWikiTools.askWikiLibrarian`) then runs the librarian on whatever the **WIKI role** resolves to (`chooseLibrarianExecution`, in [LibrarianMode.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/wiki/LibrarianMode.kt)):

- **Claude WIKI role** → a `claude -p` subprocess ([ClaudeSubprocessLibrarian.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/wiki/ClaudeSubprocessLibrarian.kt)), read-only via an `--allowedTools` allowlist over the ClawDEA MCP tools.
- **openai-compatible WIKI role** → the in-process `AgenticLibrarian` HTTP loop.
- **Codex WIKI role** → `codex exec --json` ([CodexExecLibrarian.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/wiki/CodexExecLibrarian.kt)).

**Why the Codex librarian forgoes ClawDEA's MCP tools.** The `codex exec` spike (`docs/superpowers/specs/2026-07-14-codex-interface-findings.md`) established that MCP `tools/call` only executes under `-s danger-full-access`; any real sandbox (`read-only`/`workspace-write`) blocks the loopback MCP socket on macOS Seatbelt. Wiring the MCP server would therefore force `danger-full-access` and leave codex's own shell ungated — the opposite of a read-only librarian. So `CodexExecLibrarian` runs `-s read-only -c approval_policy="never"` with **no MCP server**: codex reads the on-disk wiki (`docs/llm-wiki/…`) and greps the tree with its built-in shell (read commands need no escalation; a write escalation just fails under `never`, never executing and never hanging). The tradeoff vs. the Claude/agentic paths is no `record_wiki_suggestion` gap-logging and no IntelliJ index tools — acceptable for read-only Q&A. Unlike the app-server chat backend, this parses the **`codex exec --json`** stream (`thread.started` / `item.completed` `agent_message` / `turn.completed`), not the app-server notification stream.
