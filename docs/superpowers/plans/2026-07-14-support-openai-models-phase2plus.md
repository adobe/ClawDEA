# OpenAI Models (Codex CLI Chat Backend) — Phase 2+ Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** With Phase 1's OpenAI provider/credentials/catalog foundation in place, wire the `codex` CLI as a live second agentic backend so GPT chat reaches full parity with the Claude chat experience.

**Architecture:** Extend ClawDEA's "wrap an agentic CLI + expose the IDE via MCP" design with the `codex` CLI. All Codex output is normalized into the existing `CliEvent` sealed hierarchy so `ChatPanel`, `TurnStateMachine`, `EditReviewCoordinator`, and `CostTracker` are unchanged. Provider selection reuses the existing model-dropdown plumbing; edit review reuses the existing `propose_edit` MCP tools.

**Tech Stack:** Kotlin (JVM 21), IntelliJ Platform 2026.1, `java.net.http.HttpClient`, JUnit, PasswordSafe (`CredentialAttributes`), the `codex` CLI (`codex-cli 0.144.4`).

## Global Constraints

- **Kotlin only** — no Java sources; target JVM 21.
- **Provider id string is `"openai"`** everywhere (settings, `AuthManager`, catalogs, cost totals).
- **Secrets never serialize** — API keys live in PasswordSafe via `CredentialAttributes`, cleared in `getState()`.
- **Probes and HTTP calls must run off-EDT.**
- **Both Claude and OpenAI must work side by side** — no behavioral change to existing paths.
- Build check: `./gradlew compileKotlin`. Unit tests: `./gradlew test`. Full build (IDE running): `./gradlew build -x buildSearchableOptions`.

## Source of truth

This plan is grounded in the verified spike findings: `docs/superpowers/specs/2026-07-14-codex-interface-findings.md` (`codex-cli 0.144.4`, captured 2026-07-14). Section references below (e.g. *findings → ## Streaming mode*) point at the captured command output that justifies each interface decision.

> **⚠️ Auth-blocked gaps carried into Phase 2 (from findings → ## Event schema samples):** the spike could not capture the **success-path** events because codex was not logged in. Before implementing `CodexEventParser` (Task 2), **re-run the live turn once credentials exist** to capture: (1) `item.completed` shapes for `agent_message` / `reasoning` / `command_execution` / `mcp_tool_call`, (2) whether an incremental text-delta event exists, (3) the `turn.completed` token-usage/context-window payload. Also confirm the `model_reasoning_effort` enum (findings → ## Model & effort flags).

## TDD note

Every task below is a **skeleton** — `Interfaces` are pinned from the findings, but the **TDD steps (Red/Green/Refactor) are intentionally left blank** and must be written at the start of Phase 2, after the auth-blocked success-path samples are captured. Do not implement from this skeleton without filling the steps.

---

## Phase 2 — Wire Codex as a live backend

### Task 1: `CodexProcess` — spawn and stream the CLI

**Files:**
- Create: `src/main/kotlin/com/adobe/clawdea/cli/CodexProcess.kt`
- Test: `src/test/kotlin/com/adobe/clawdea/cli/CodexProcessTest.kt`

**Interfaces:**
- **Spawn command (findings → ## Streaming mode):** `codex exec --json` with `--skip-git-repo-check` as needed, `-C <projectBase>` (working root), `-m <model>` (findings → ## Model & effort flags), and `-c model_reasoning_effort=<value>`.
- **stdin (findings → ## Streaming mode, stdin caveat):** MUST redirect/close the child's stdin (write prompt then close, or `</dev/null`) — `codex exec` blocks on `Reading additional input from stdin...` until EOF otherwise.
- **Binary resolution:** mirror `resolveClaudeCliPath()` in `CliProcess.kt` (Finder/Dock PATH problem) for `codex` at `~/.nvm/.../bin/codex`.
- **Resume (findings → ## Session resume):** `codex exec resume <session-id> "<prompt>"` / `--last`; session id == `thread.started.thread_id`.
- **Approval/sandbox (findings → ## Approval mechanism):** pass `-a never` + `-s <sandbox_mode>` (or `--dangerously-bypass-approvals-and-sandbox` only under explicit opt-in); route file mutations through MCP edit tools, not Codex's own approvals.
- Produces: raw NDJSON lines → `CodexEventParser`. Consumes: prompt text, model, effort, sessionId from `CliBridge`.

**Steps (TDD — fill at Phase 2 start):**
- [ ] _Red:_ TBD
- [ ] _Green:_ TBD
- [ ] _Refactor:_ TBD

### Task 2: `CodexEventParser` — normalize the JSON stream to `CliEvent`

**Files:**
- Create: `src/main/kotlin/com/adobe/clawdea/cli/CodexEventParser.kt`
- Test: `src/test/kotlin/com/adobe/clawdea/cli/CodexEventParserTest.kt`

**Interfaces (findings → ## Event schema samples):** parse the `codex exec --json` **stdout** thread/turn/item schema (NOT the on-disk rollout schema) into `CliEvent` (`src/main/kotlin/com/adobe/clawdea/cli/CliEvent.kt`):

| Codex event | → `CliEvent` |
|---|---|
| `thread.started` (`thread_id`) | `SystemInit(sessionId = thread_id, model, tools)` |
| `turn.started` | lifecycle only (no event) |
| `item.completed` type `agent_message` | `AssistantMessage` (+ `TextDelta` if delta variant exists) |
| `item.completed` type `command_execution` / `mcp_tool_call` | `ToolUse` + `ToolResult` |
| `item.completed` type `reasoning` | reasoning render (no direct type) |
| `turn.completed` (`usage`) | `Result` (token counts + `contextWindow`) |
| `turn.failed` / terminal `error` (401) | `Result(isError=true)` or `AuthFailure` |

- Follow the manual-string-extraction convention of `CliEventParser` (no Gson) for the high-volume stream.
- **Blocked on:** captured success-path samples (see auth gaps above).

**Steps (TDD — fill at Phase 2 start):**
- [ ] _Red:_ TBD
- [ ] _Green:_ TBD
- [ ] _Refactor:_ TBD

### Task 3: MCP wiring / transport adapter

**Files:**
- Modify: MCP registration path used by `CodexProcess`; possibly `McpServer.kt`.
- Test: TBD MCP-registration test.

**Interfaces (findings → ## MCP transport):** Codex consumes a **streamable HTTP** MCP server via
`codex mcp add <name> --url <URL>` (with `--bearer-token-env-var`), config persisted as TOML in
`~/.codex/config.toml` (or inline `-c 'mcp_servers.<name>.url="http://127.0.0.1:<port>/..."'`).
- **Decision gate:** verify ClawDEA's `McpServer` speaks the MCP **Streamable HTTP** transport
  (single endpoint, POST + optional SSE, `Mcp-Session-Id`). If yes → register `--url` directly
  (no adapter). If ClawDEA's server is plain JSON-RPC-over-HTTP → either upgrade `McpServer` to
  Streamable-HTTP (preferred, avoids a subprocess) or add a stdio→HTTP shim registered via
  `codex mcp add clawdea -- <shim>`.
- Prefer a per-invocation temp config (mirror `CliProcess`'s temp MCP config) over mutating the
  user's `~/.codex/config.toml`.

**Steps (TDD — fill at Phase 2 start):**
- [ ] _Red:_ TBD
- [ ] _Green:_ TBD
- [ ] _Refactor:_ TBD

### Task 4: Edit-review integration

**Files:**
- Modify: `EditReviewCoordinator` / edit-review wiring for the Codex path.
- Test: TBD.

**Interfaces (findings → ## Approval mechanism):** Codex has **no `--permission-prompt-tool`
equivalent** — it only has native `-a/--ask-for-approval` + `-s/--sandbox`. Therefore:
- **Layer 1 (MCP tools):** keep `propose_edit`/`propose_write`/`propose_multi_edit` — Codex calls
  these MCP tools and ClawDEA gates the diff inside the tool handler (unchanged from Claude).
- **Layer 2 (fallback):** run Codex with `-a never` + a sandbox mode so Codex's built-in shell edits
  don't silently mutate; capture `command_execution` items and surface / gate them.
- Decide whether to surface Codex's native approval-escalation events in the UI (needs the
  auth-blocked escalation event shape).

**Steps (TDD — fill at Phase 2 start):**
- [ ] _Red:_ TBD
- [ ] _Green:_ TBD
- [ ] _Refactor:_ TBD

---

## Phase 3 — Auth, model catalog, cost

### Task 5: `CodexSubscriptionAuth` + probe

**Files:**
- Create: `CodexSubscriptionAuth.kt`, `CodexSubscriptionAuthProbe.kt` (mirror `SubscriptionAuth`/`SubscriptionAuthProbe`).
- Test: TBD.

**Interfaces (findings → ## Auth cache & login flow):**
- **ChatGPT sign-in:** `codex login` (browser / `--device-auth`) — analogue of Claude subscription sign-in.
- **API key:** `printenv OPENAI_API_KEY | codex login --with-api-key` (key read from stdin).
- **Status probe:** `codex login status` → `"Not logged in"` when unauthenticated (parse this); `codex doctor` as secondary.
- **Credential cache:** `$CODEX_HOME` (default `~/.codex/`), file `~/.codex/auth.json` (created on login).
- **Failure mapping:** terminal `401 Unauthorized` on the stream → `AuthFailure` (findings → ## Event schema samples).

**Steps (TDD — fill at Phase 2 start):**
- [ ] _Red:_ TBD
- [ ] _Green:_ TBD
- [ ] _Refactor:_ TBD

### Task 6: `OpenAIModelProbe` live refresh

**Files:**
- Create/extend: OpenAI branch of `ModelCatalogProbe` (`OpenAIModelProbe`).
- Test: TBD.

**Interfaces (findings → ## Model & effort flags):**
- Model passed via `-m <model>` / `-c model="..."`; effort via `-c model_reasoning_effort=<value>`.
- Effort dropdown mapping: Codex accepts `minimal|low|medium|high` (**confirm enum once authed**);
  ClawDEA's `xhigh`/`max` have no Codex equivalent — collapse (`xhigh`→`high`, `max`→`high`).
- Probe OpenAI model list off-EDT (mirror the existing Anthropic/Bedrock probe pattern).

**Steps (TDD — fill at Phase 2 start):**
- [ ] _Red:_ TBD
- [ ] _Green:_ TBD
- [ ] _Refactor:_ TBD

### Task 7: `ModelPricing` entries for OpenAI

**Files:**
- Modify: `ModelPricing` (add OpenAI/GPT model $ / token rates).
- Test: TBD.

**Interfaces (findings → ## Event schema samples):** Codex reports **tokens, not dollars**
(`turn.completed.usage`, auth-blocked — re-capture). `CostTracker` derives `Result.costUsd` from
per-model pricing entries × token counts (`inputTokens`/`outputTokens`/`cacheReadTokens`). Add GPT
model rows so the existing cost footer works for the `"openai"` provider.

**Steps (TDD — fill at Phase 2 start):**
- [ ] _Red:_ TBD
- [ ] _Green:_ TBD
- [ ] _Refactor:_ TBD

---

## Phase 4 — Parity

### Task 8: Debugger / skills / wiki parity

**Files:**
- Modify: skill/debugger/wiki wiring so the Codex backend reaches the same tool surface as Claude.
- Test: TBD.

**Interfaces:**
- **Skills (findings → ## Session resume rollout sample):** Codex has its own skills mechanism
  (`$CODEX_HOME/skills`, `SKILL.md`) surfaced in the session's developer message — decide whether
  ClawDEA's skill layer maps onto Codex skills or is exposed purely via MCP tools.
- **Debugger / wiki:** exposed through the same MCP server (findings → ## MCP transport), so parity
  is contingent on Task 3's transport decision. No Codex-specific flags required beyond MCP registration.

**Steps (TDD — fill at Phase 2 start):**
- [ ] _Red:_ TBD
- [ ] _Green:_ TBD
- [ ] _Refactor:_ TBD
