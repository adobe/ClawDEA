# Codex CLI Interface — Findings (Phase 0 spike)

Spike to de-risk wrapping the OpenAI `codex` CLI as a second agentic backend in
ClawDEA (mirroring the existing `claude` wrapper). Every claim below is backed by
a command + its captured output, run on **2026-07-14** against **`codex-cli 0.144.4`**.

> **Auth status during this spike:** `codex login status` reported **`Not logged in`**
> and no `OPENAI_API_KEY` was set. Live model turns therefore fail with `401 Unauthorized`.
> This is called out per-section. The 401 output is itself useful (it documents the
> `AuthFailure`/`turn.failed` mapping and confirms the stream shape), but any claim that
> depends on a **successful** turn — specifically the success-path `item.completed`
> (assistant message / reasoning / command / MCP tool-call) shapes and the
> `turn.completed` token-usage payload — **must be re-captured once credentials exist.**
> Those are the only gaps; everything else is verified locally.

---

## Environment

```bash
$ which codex && codex --version
/Users/spopescu/.nvm/versions/node/v22.3.0/bin/codex
codex-cli 0.144.4
```

Installed via npm (`@openai/codex`) at
`/Users/spopescu/.nvm/versions/node/v22.3.0/lib/node_modules/@openai/codex`. **Not reinstalled.**

Top-level subcommands (`codex --help`, abridged):

```
Usage: codex [OPTIONS] [PROMPT]
       codex [OPTIONS] <COMMAND> [ARGS]

Commands:
  exec            Run Codex non-interactively [aliases: e]
  review          Run a code review non-interactively
  login           Manage login
  logout          Remove stored authentication credentials
  mcp             Manage external MCP servers for Codex
  mcp-server      Start Codex as an MCP server (stdio)
  resume          Resume a previous interactive session (picker by default; --last)
  fork            Fork a previous interactive session
  doctor          Diagnose local Codex installation, config, auth, and runtime health
  login/logout, archive/delete/unarchive, apply, cloud, features, ...
```

Global options relevant to ClawDEA (`codex --help`):

```
  -c, --config <key=value>   Override config that would load from ~/.codex/config.toml (TOML value)
  -m, --model <MODEL>        Model the agent should use
  -s, --sandbox <MODE>       [possible values: read-only, workspace-write, danger-full-access]
  -a, --ask-for-approval <APPROVAL_POLICY>   [untrusted | on-request | never]
      --dangerously-bypass-approvals-and-sandbox
  -C, --cd <DIR>             Working root
      --add-dir <DIR>        Additional writable dirs
```

`~/.codex/` (a.k.a. `$CODEX_HOME`) is the config/state/auth home. On a fresh install it
contains only `tmp/`; running turns populates `sessions/`, `shell_snapshots/`, several
`*.sqlite` state DBs, `installation_id`. **No `config.toml` exists by default** (it is created
on first `-c` persist / `codex mcp add`), and **no `auth.json` exists** because we are not logged in.

---

## Streaming mode

**Machine-readable streaming mode: `codex exec --json`.** It prints one JSON object per line
(**JSONL / NDJSON**) to stdout.

> ⚠️ There is **no `proto` subcommand** in 0.144.4 (the brief's example `codex proto` is stale).
> `codex proto` is *not* in the command list; invoking it forwards to the interactive TUI, which
> aborts in a non-TTY:
> ```
> $ echo '{}' | codex proto
> ERROR: TERM is set to "dumb". Refusing to start the interactive TUI because no terminal is available ...
> ```

The flag is documented under `codex exec --help`:

```
      --json
          Print events to stdout as JSONL
      --output-schema <FILE>
          Path to a JSON Schema file describing the model's final response shape
  -o, --output-last-message <FILE>
          Specifies file where the last message from the agent should be written
      --skip-git-repo-check
          Allow running Codex outside a Git repository
      --ephemeral
          Run without persisting session files to disk
      --color <COLOR>   [always | never | auto]
```

- **Line-oriented:** yes — each event is a standalone JSON object terminated by `\n`.
- **Format:** JSONL (NDJSON).
- **Partial/token deltas:** The observed event vocabulary is the newer **thread / turn / item**
  model (`thread.started`, `turn.started`, `item.started`/`item.completed`, `turn.completed`,
  `turn.failed`, `error`). Item-level events are **coarse-grained** (a whole item completes at once)
  rather than per-token text deltas. Whether an incremental `item.updated`/delta variant is emitted
  on the success path is **unverified (auth-blocked)** — see *Event schema samples*.

**stdin caveat (important for `CodexProcess`):** `codex exec` reads the prompt from a positional
arg, but when **stdin is not a TTY it also reads/append stdin as a `<stdin>` block and blocks until
EOF.** In the first spike run (stdin left as an open pipe) the process hung on
`Reading additional input from stdin...` until the 60s timeout (exit 124). Redirecting
`</dev/null` fixed it. **`CodexProcess` must close/redirect the child's stdin (or write the prompt
to stdin and close it).**

---

## MCP transport

**Codex-as-MCP-client can consume an HTTP MCP server** — it supports the **Streamable HTTP**
transport in addition to stdio. This is the single most important finding: ClawDEA's existing
`McpServer` (HTTP on `127.0.0.1:<random port>`) can be wired directly, **no stdio↔HTTP adapter
is required**, *provided ClawDEA's server speaks the MCP Streamable HTTP transport* (see caveat).

Evidence — `codex mcp add --help`:

```
Usage: codex mcp add [OPTIONS] <NAME> (--url <URL> | -- <COMMAND>...)

Arguments:
  <NAME>            Name for the MCP server configuration
  [COMMAND]...      Command to launch the MCP server. Use --url for a streamable HTTP server

Options:
      --url <URL>                    URL for a streamable HTTP MCP server
      --bearer-token-env-var <VAR>   Bearer token env var. Only valid with streamable HTTP servers
      --oauth-client-id <CLIENT_ID>  Optional OAuth client identifier
      --oauth-resource <RESOURCE>    Optional OAuth resource parameter
      --env <KEY=VALUE>              Env vars for the server. Only valid with stdio servers
```

So each configured MCP server is **either**:
- **stdio**: `codex mcp add <name> -- <command> [args...]` (with `--env`), or
- **streamable HTTP**: `codex mcp add <name> --url <URL>` (with optional `--bearer-token-env-var`
  / OAuth params).

**Config format & location:** TOML at `~/.codex/config.toml` (`$CODEX_HOME/config.toml`).
`codex mcp add` writes into it; it can also be supplied inline via repeated `-c` overrides
(`-c 'mcp_servers.<name>.url="http://127.0.0.1:PORT/..."'`). `codex mcp list --json` on the empty
default config returns `[]`:

```
$ codex mcp list --json
[]
```

**Caveat to verify in Phase 2:** "streamable HTTP" is the specific MCP *Streamable HTTP* transport
(single endpoint, POST + optional SSE stream, `Mcp-Session-Id` header). ClawDEA's `McpServer` must
implement that transport shape for `--url` to connect. If ClawDEA's current HTTP server is a plain
JSON-RPC-over-HTTP handler (not Streamable-HTTP-compliant), Phase 2 must either (a) make `McpServer`
Streamable-HTTP-compliant, or (b) register ClawDEA as a **stdio** MCP server via a thin
stdio→HTTP shim (`codex mcp add clawdea -- <shim>`). Preferred path (a) since it avoids a subprocess.
`codex mcp-server` is the *inverse* (codex acting as a server); not relevant to exposing ClawDEA.

---

## Model & effort flags

**Model selection:** `-m, --model <MODEL>` (top-level and on `exec`), or `-c model="..."`.
From `codex --help`:

```
  -m, --model <MODEL>   Model the agent should use
  # config example:  -c model="o3"
```

**Reasoning effort:** there is **no `--effort`/`--reason` flag**. `codex --help | grep -iE 'effort|reason'`
returns nothing. Effort is a **config key**: `-c model_reasoning_effort=<value>` (or
`model_reasoning_effort = "..."` in `config.toml`).

The key is **recognized** — proven with `--strict-config` (which errors on unknown keys):

```
$ codex exec --json --strict-config -c model_reasoning_effort=high "ok" </dev/null
{"type":"thread.started","thread_id":"019f5fc4-0339-..."}      # accepted, proceeds

$ codex exec --json --strict-config -c totally_bogus_key=1 "ok" </dev/null
Error loading config.toml: unknown configuration field `totally_bogus_key` in -c/--config override
```

**Accepted effort enum values — NOT verifiable locally (auth-blocked).** `--strict-config`
validates config *keys* but **not** the *value* of `model_reasoning_effort`: even
`-c model_reasoning_effort=bogusvalue` reached `thread.started` (value validation is deferred to the
request-build/server step, which is auth-blocked with 401). Per OpenAI Codex docs the accepted set is
`minimal | low | medium | high`. **Confirm the exact enum once authed.**

**Mapping to ClawDEA's effort dropdown (`low`, `medium`, `high`, `xhigh`, `max`):** `low`/`medium`/`high`
map 1:1 to the Codex values. Codex has **no `xhigh`/`max`** — Phase 2 must decide a collapse
(likely `xhigh`→`high`, `max`→`high`, and optionally surface `minimal`). This is a Phase 2 design call,
recorded here so the plan cites a real constraint.

---

## Approval mechanism

**Codex has NO external permission-prompt-tool hook** — there is no equivalent of Claude's
`--permission-prompt-tool` (which lets ClawDEA gate every tool call through an MCP tool).
`codex --help | grep -iE 'permission|prompt-tool'` finds only the `sandbox_permissions` config example,
no prompt-tool flag.

Approval is **native only**, via two axes:

```
  -a, --ask-for-approval <APPROVAL_POLICY>
        untrusted   Only run "trusted" commands (ls, cat, sed) without asking; escalate otherwise
        on-request  The model decides when to ask the user for approval
        never       Never ask; execution failures are returned to the model
  -s, --sandbox <SANDBOX_MODE>   [read-only | workspace-write | danger-full-access]
      --dangerously-bypass-approvals-and-sandbox   (skip all prompts + sandbox — EXTREMELY DANGEROUS)
```

In non-interactive `exec` there is no TTY to answer an interactive approval, so an escalation
effectively blocks/aborts. Confirmed in the rollout of a `read-only` turn — Codex injects a
developer message describing the active policy:

```
"<permissions instructions>\nFilesystem sandboxing ... `sandbox_mode` is `read-only` ...
Approval policy is currently never. Do not provide the `sandbox_permissions` ...\n</permissions instructions>"
```

**Implication for edit review:** ClawDEA's `propose_edit`/`propose_write` MCP-tool approach still
works — those are *MCP tools* Codex calls, and ClawDEA gates the diff *inside the tool handler*
(same as today). What ClawDEA **cannot** do with Codex is intercept Codex's **own** shell-command
approvals through an MCP prompt tool. Phase 2 approach: run Codex with `-a never` + a sandbox mode
and route all file mutation through ClawDEA's MCP edit tools, OR surface Codex's native approval
events (`item`/turn escalations) in the UI. Requires the success-path escalation event shape
(auth-blocked) to finalize.

---

## Session resume

Sessions persist as **rollout JSONL files** under
`~/.codex/sessions/YYYY/MM/DD/rollout-<ts>-<uuid>.jsonl`. The `<uuid>` is the **session/thread id**
(same value as the `thread.started` `thread_id` on the stream).

Resume flags:

```
$ codex exec resume --help
Usage: codex exec resume [OPTIONS] [SESSION_ID] [PROMPT]
  [SESSION_ID]  Conversation/session id (UUID) or thread name
      --last    Resume the most recent recorded session (newest) without specifying an id
      --all     Show all sessions (disables cwd filtering)
```

- Non-interactive resume: **`codex exec resume <session-id> "<prompt>"`** or `codex exec resume --last "<prompt>"`.
- Interactive equivalents: `codex resume` / `codex fork` (picker or `--last`).
- `--ephemeral` on `exec` disables session persistence entirely.

Rollout file head (captured) confirms the persisted id and metadata `CodexProcess` can use to map
resume ↔ ClawDEA's `SystemInit.sessionId`:

```json
{"type":"session_meta","payload":{"session_id":"019f5fc5-56ce-...","id":"019f5fc5-56ce-...",
  "cwd":"/Users/spopescu/Work/aem/ClawDEA","originator":"codex_exec","cli_version":"0.144.4",
  "source":"exec","model_provider":"openai","git":{"branch":"feat/openai-codex-chat-backend", ...}}}
{"type":"event_msg","payload":{"type":"task_started","turn_id":"019f5fc5-5726-...",
  "model_context_window":353400,"collaboration_mode_kind":"default"}}
```

> Note: the **rollout file** schema (`session_meta` / `event_msg` / `response_item`) is the on-disk
> persistence format and is **distinct** from the `codex exec --json` **stdout** stream schema
> (`thread.*` / `turn.*` / `item.*`). `CodexEventParser` parses the **stdout stream**; the rollout
> file is only relevant for resume bookkeeping. `task_started.model_context_window` (here `353400`)
> is a candidate source for `Result.contextWindow` if it also appears on the stdout stream (verify
> on the success path).

---

## Auth cache & login flow

`codex login` manages auth; credentials live under **`$CODEX_HOME` (default `~/.codex/`)** — the
`--ignore-user-config` help explicitly notes "auth still uses `CODEX_HOME`". The standard credential
file is `~/.codex/auth.json` (**absent here because not logged in** — cannot be shown).

```
$ codex login --help
Usage: codex login [OPTIONS] [COMMAND]
Commands:
  status  Show login status
Options:
      --with-api-key         Read the API key from stdin (e.g. `printenv OPENAI_API_KEY | codex login --with-api-key`)
      --with-access-token    Read the access token from stdin
      --device-auth
```

- **ChatGPT sign-in:** bare `codex login` (interactive; opens browser / device-auth via `--device-auth`).
  This is the analogue of Claude's subscription sign-in for `CodexSubscriptionAuth`.
- **API key:** `printenv OPENAI_API_KEY | codex login --with-api-key` (reads key from stdin).
- **Status probe (analogue of `claude auth status`):**

```
$ codex login status
Not logged in
```

`codex doctor` also reports auth/runtime health and is a candidate secondary probe.

**Live-turn auth block (documents the failure mapping):** with no credentials, a turn fails after
retrying WebSocket then HTTPS transport — see the 401 sample in *Event schema samples*. Once
credentials exist, re-run Step 3 to capture the success-path events.

---

## Event schema samples

Captured stream (`codex exec --json --skip-git-repo-check "reply with the word ok" </dev/null`),
**auth-blocked (401)** but showing the real event envelope shape. Annotated against the existing
`CliEvent` sealed hierarchy in `src/main/kotlin/com/adobe/clawdea/cli/CliEvent.kt`:

```json
{"type":"thread.started","thread_id":"019f5fc3-5a8a-71b1-aa70-99602f3e1dde"}
{"type":"turn.started"}
{"type":"error","message":"Reconnecting... 2/5 (unexpected status 401 Unauthorized: Missing bearer or basic authentication in header, url: wss://api.openai.com/v1/responses ...)"}
{"type":"item.completed","item":{"id":"item_0","type":"error","message":"Falling back from WebSockets to HTTPS transport. unexpected status 401 Unauthorized ..."}}
{"type":"error","message":"Reconnecting... 5/5 (unexpected status 401 Unauthorized ... url: https://api.openai.com/v1/responses, request id: req_...)"}
{"type":"error","message":"unexpected status 401 Unauthorized: Missing bearer or basic authentication in header, url: https://api.openai.com/v1/responses, request id: req_..."}
{"type":"turn.failed","error":{"message":"unexpected status 401 Unauthorized: Missing bearer or basic authentication in header ..."}}
```

### Mapping to `CliEvent`

| Codex stream event | Fields | → `CliEvent` |
|---|---|---|
| `thread.started` | `thread_id` | **`SystemInit`** — `thread_id` → `sessionId`. `model`/`tools` not on this event; source them from the `-m`/config value and MCP tool list (or a later event). |
| `turn.started` | — | Turn lifecycle marker. **No `CliEvent` equivalent** (drive `TurnStateMachine` Idle→Streaming). |
| `item.completed` `item.type == "agent_message"` *(success path — NOT yet captured, auth-blocked)* | `item.text`/message | **`AssistantMessage`** (and/or `TextDelta` if a delta/`item.updated` variant exists). **Re-capture once authed.** |
| `item.completed` `item.type == "reasoning"` *(auth-blocked)* | reasoning summary | No direct `CliEvent`; render as reasoning/thinking. **Re-capture.** |
| `item.completed` `item.type == "command_execution"` *(auth-blocked)* | command, output | **`ToolUse`** + **`ToolResult`** (Codex's built-in shell). **Re-capture.** |
| `item.completed` `item.type == "mcp_tool_call"` *(auth-blocked)* | server/tool/args/result | **`ToolUse`** + **`ToolResult`**. **Re-capture.** |
| `item.completed` `item.type == "error"` | `item.id`, `item.message` | Transient/item-level error → surface as error line. |
| `turn.completed` *(success path — NOT yet captured, auth-blocked)* | `usage` (input/output/cached tokens), context window | **`Result`** — token counts → `inputTokens`/`outputTokens`/`cacheReadTokens`/`contextTokens`/`contextWindow`; `costUsd` derived from pricing (Codex reports tokens, not $). **Re-capture — this is the key gap for `CostTracker`.** |
| `turn.failed` | `error.message` | **`Result(isError = true)`**; if the message is a 401/auth error, classify as **`AuthFailure`**. |
| `error` (top-level) | `message` | Transient connection error line; a terminal 401 here → **`AuthFailure`**. |

**No `CliEvent` equivalent (Codex-only):** `turn.started` (lifecycle). ClawDEA has no analogue of
`Result.sessionId` being separate from `SystemInit` — Codex reuses the same `thread_id`, so
`sessionId` is set once at `thread.started`.

**Gaps to re-capture once authed (Step 3 success path):**
1. `item.completed` shapes for `agent_message`, `reasoning`, `command_execution`, `mcp_tool_call`.
2. Whether an incremental text-delta event (`item.updated`/`item.delta`) exists (drives `TextDelta`).
3. `turn.completed` token-usage / context-window payload (drives `Result` + cost).

---

## Phase 2 spike closure (2026-07-14, authed via ChatGPT)

The gaps above are now **closed** — re-captured against a live, logged-in `codex login status → Logged in using ChatGPT`.

### 1. Success-path event schema (captured `codex exec --json` stdout)

Trivial turn (`"reply with exactly the word: ok"`):

```json
{"type":"thread.started","thread_id":"019f608e-0d7d-77c0-89a4-986abb699e00"}
{"type":"turn.started"}
{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"ok"}}
{"type":"turn.completed","usage":{"input_tokens":12750,"cached_input_tokens":9984,"output_tokens":5,"reasoning_output_tokens":0}}
```

Tool-use turn (shell `echo`):

```json
{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"I’ll run the requested command and report its output."}}
{"type":"item.started","item":{"id":"item_1","type":"command_execution","command":"/bin/zsh -lc 'echo hello-from-codex'","aggregated_output":"","exit_code":null,"status":"in_progress"}}
{"type":"item.completed","item":{"id":"item_1","type":"command_execution","command":"/bin/zsh -lc 'echo hello-from-codex'","aggregated_output":"hello-from-codex\n","exit_code":0,"status":"completed"}}
{"type":"item.completed","item":{"id":"item_2","type":"agent_message","text":"I ran `echo hello-from-codex` successfully. It printed `hello-from-codex`."}}
{"type":"turn.completed","usage":{"input_tokens":24270,"cached_input_tokens":19968,"output_tokens":104,"reasoning_output_tokens":0}}
```

MCP tool-call turn (against a mock of ClawDEA's `McpServer`):

```json
{"type":"item.started","item":{"id":"item_1","type":"mcp_tool_call","server":"clawdea","tool":"clawdea_ping","arguments":{},"result":null,"error":null,"status":"in_progress"}}
{"type":"item.completed","item":{"id":"item_1","type":"mcp_tool_call","server":"clawdea","tool":"clawdea_ping","arguments":{},"result":{"content":[{"type":"text","text":"pong-42"}],"structured_content":null},"error":null,"status":"completed"}}
```

**Resolved gaps:**
1. `agent_message` arrives as a **single coarse `item.completed`** (`item.text`) — **multiple per turn** (a preamble item + the final answer item). `command_execution` and `mcp_tool_call` are two-phase: `item.started` (`status:"in_progress"`) then `item.completed` (`status:"completed"|"failed"`, with `aggregated_output`+`exit_code` / `result`+`error`). No `reasoning` item appeared at default effort (`reasoning_output_tokens:0`).
2. **No incremental text-delta event exists on the success path** — text is delivered whole in `item.completed`. `CodexEventParser` maps each `agent_message` → a full `AssistantMessage` (no `TextDelta`).
3. `turn.completed.usage = {input_tokens, cached_input_tokens, output_tokens, reasoning_output_tokens}`. **No dollar cost and no context-window field** on the stdout stream → `Result.costUsd` must be derived from per-model pricing × tokens (`cached_input_tokens` → `cacheReadTokens`); `contextWindow` unavailable from the stream (fall back to a per-model default).

### 2. MCP transport — DIRECTLY compatible, no adapter (decision for Task 3)

A faithful standalone mock of ClawDEA's `McpServer` protocol (stateless JSON-RPC over HTTP at `POST /mcp`, single `application/json` responses, `405` on GET, `protocolVersion "2025-03-26"`) was registered with codex via `-c 'mcp_servers.clawdea.url="http://127.0.0.1:PORT/mcp"'`. Codex's Rust `rmcp` client completed the **full handshake and called the tool**:

```
POST initialize (id 0, Accept: "text/event-stream, application/json")
POST notifications/initialized
POST tools/list (id 1)
POST tools/call (id 2)  → result {"content":[{"type":"text","text":"pong-42"}]}
```

- **Requirement: HTTP/1.1 keep-alive.** The first mock defaulted to HTTP/1.0 (connection closed after each request); codex's `rmcp` reuses the connection and failed with `worker quit with fatal: Transport channel closed ... error sending request`. ClawDEA's real server uses `com.sun.net.httpserver.HttpServer`, which keeps connections alive by default → **compatible as-is**. **No stdio↔HTTP shim and no Streamable-HTTP rewrite of `McpServer` are needed.**
- **Register per-invocation via `-c` overrides** (mirrors `CliProcess`'s temp MCP config) rather than mutating `~/.codex/config.toml`.

### 3. Approval / sandbox — required flags for non-interactive MCP tool calls

MCP tool **calls** are gated and only execute non-interactively under:

```
-s danger-full-access  -c 'approval_policy="never"'
```

(equivalently `--dangerously-bypass-approvals-and-sandbox`). Under `-s workspace-write` — **even with `-c sandbox_workspace_write.network_access=true`** — the `tools/call` never reaches the server and the item completes with `error:{"message":"user cancelled MCP tool call"}`. The `initialize`/`tools/list` handshake works under any sandbox mode; only `tools/call` is gated. **Production posture:** run `codex exec` with `approval_policy=never` + `danger-full-access` and rely on ClawDEA's own `propose_edit`/`propose_write` MCP gating for file-mutation safety (parity with the Claude `--permission-prompt-tool` model). Tightening codex's built-in shell is a follow-up.

### 4. Flag placement / process model

- **`-a`/`--ask-for-approval` and `-m`/`--model` are TOP-LEVEL flags** (must precede `exec`) — `codex exec -a …` errors with `unexpected argument '-a'`. Use `-c approval_policy=…` / `-c model_reasoning_effort=…` on `exec`, or place `-m` before `exec`. `-s`, `-C`, `--json`, `--skip-git-repo-check`, `--ephemeral`, `-c` are accepted on `exec`.
- **`exec` is one-shot per turn.** The prompt is a positional arg; multi-turn continuity is `codex exec resume <thread_id> "<prompt>"`. Even with `</dev/null` codex prints `Reading additional input from stdin...` to stderr but proceeds — parse **only** stdout lines beginning with `{`; stderr also carries a benign `failed to refresh available models` line. `CodexProcess` presents a **persistent facade** over successive one-shot `exec`/`exec resume` invocations (sniff `thread_id` from `thread.started` for the next resume).

---

## Phase 2+ delivery notes (2026-07-14, re-verified live)

### Permission gating (Task 4) — resolved to a design decision + Layer-1 edit routing

- **Re-confirmed the sandbox constraint decisively.** A loopback MCP mock was registered and codex asked to call it. Under `-s workspace-write` **with `-c sandbox_workspace_write.network_access=true`**, the `rmcp` client fails the handshake outright (`http/request failed: error sending request for url http://127.0.0.1:PORT/mcp` — macOS Seatbelt blocks the loopback socket). Under `-s danger-full-access` the full handshake + `tools/call` succeed and the mock logs the hit. **There is no sandbox mode that both gates codex's built-in shell and permits the loopback MCP server.** `danger-full-access` is required.
- **Layer-1 edit review works and is validated live.** `CodexProcess` injects a first-turn preamble (`prompts/codex-tooling-prompt.md`) instructing codex to route all file mutations through the `clawdea` MCP `propose_edit`/`propose_write`/`propose_multi_edit` tools. Verified: given "create /tmp/…", codex called `clawdea.propose_write` (MCP hit logged) and did **not** write the file directly. This is the codex analogue of Claude's Layer-1 `propose_*` gating.
- **Residual gap (documented, not closed):** under `danger-full-access` codex's built-in shell (`command_execution`) still executes ungated. It is surfaced in the ClawDEA UI (ToolUse/ToolResult via `CodexEventParser`) for transparency, but there is no pre-execution approval prompt (codex `exec` has no interactive approval channel, and the sandbox that would gate it breaks MCP — see above). Tightening this would require intercepting codex shell items before execution, which codex `exec` does not currently expose.

### Skills + primer parity (Task 8)

- The same first-turn preamble also carries the **skill catalog** (`CliProcess.buildSkillCatalogPrompt`, gated on `preloadSkillCatalog`) and the **project primer** (`PrimerService.refreshAndGet`, gated on `enableKnowledgeLayer`), so `/skill` suggestions and CLAUDE.md/REPO_STATE/wiki-TOC context reach codex exactly as they reach Claude. Instructions given on turn 1 persist across `exec resume`, so the preamble is sent once (skipped on resume turns and when `start` resumes an existing thread).
- Debugger / index / search / wiki tools are already reachable because they are exposed through the same MCP server (Task 3, compatible as-is).

### Model catalog + cost (Tasks 6, 7)

- **Model list:** `CodexModelProbe` reads `$CODEX_HOME/models_cache.json` (codex's own account model cache) and surfaces `visibility == "list"` models; registered for `openai-subscription` in `ModelCatalogProbes.forProvider`. The codex `exec --json` stream carries **no** model field (verified), so "Default" (no `-m`) cannot be resolved from the stream.
- **Cost:** `ModelPricing` gains a GPT family branch — GPT-5 tier (1.25/10), `-mini` (0.25/2), `-nano` (0.05/0.40) — so explicitly-selected GPT models price correctly instead of falling through to the Claude Opus fallback. Subscription turns are flat-rate; these drive only the notional estimate (same treatment as Claude subscription/bedrock).
