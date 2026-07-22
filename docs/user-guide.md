# ClawDEA User Guide

ClawDEA is native [Claude Code](https://docs.anthropic.com/en/docs/claude-code) integration for IntelliJ IDEA. Its defining feature is a **self-maintaining project wiki** — a knowledge layer that stays in sync with your code via a commit-driven drift detector, ships an orientation primer with every turn, and is ready to share across a team through git. Alongside it, ClawDEA adds **JVM profiling as a diagnostic loop** (Claude reads JFR/heap analysis and proposes source-level fixes), plus IDE-grade code navigation, diff-gated edit review, and a live debugger.

ClawDEA coexists with IntelliJ's own [bundled MCP server](https://www.jetbrains.com/help/idea/mcp-server.html): when you enable it, ClawDEA automatically stops serving the handful of tools the IDE already covers and keeps serving the ones with no IDE-native equivalent — see [Wiki team mode](#wiki-team-mode) and the feature list in the [README](../README.md#features).

The chat panel can drive three kinds of backend: **Claude Code**, **OpenAI Codex** (see [OpenAI (Codex) chat backend](#openai-codex-chat-backend)), or a verified-agentic **OpenAI-compatible provider profile** (see [OpenAI-compatible provider profiles](#openai-compatible-provider-profiles)). MCP tools, debugger, and edit review work the same across all three; inline completions and intention actions work with Claude or a selected OpenAI-compatible profile, but not with Codex.

As of **3.0**, Chat, Wiki (wiki-librarian/wiki-author), and Completions can each be pinned to a *different* provider via the **Roles** tab — see [Per-role provider selection](#roles-tab-per-role-provider-selection) — and the settings UI itself is now tabbed (Providers / Roles / Permissions / Knowledge Layer / Profiling / Advanced) rather than one flat panel.

## Getting Started

### Prerequisites

- **IntelliJ IDEA 2026.1** or later (Community or Ultimate)
- **Java 21** runtime
- **Claude Code CLI** — install with `npm install -g @anthropic-ai/claude-code`
- **OpenAI Codex CLI** (only to chat with Codex) — install with `npm install -g @openai/codex`
- An **OpenAI-compatible provider profile** (only to chat with a third-party model) — no CLI needed, see [OpenAI-compatible provider profiles](#openai-compatible-provider-profiles)
- Auth for at least one backend:
  - Claude: Anthropic API key, Claude Pro/Max/Team/Enterprise subscription, AWS Bedrock credentials, or Google Vertex credentials
  - OpenAI: a ChatGPT subscription (Plus/Pro/Team/Enterprise) or an OpenAI API key
  - OpenAI-compatible: whatever credentials the imported profile requires

### Installation

1. Download `ClawDEA-<version>.zip` from the [latest release](https://github.com/adobe/ClawDEA/releases/latest).
2. In IntelliJ: **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the zip and restart the IDE.

### Configuration

Open **Settings → Tools → ClawDEA**. Settings is organized into six tabs:

| Tab | Covers |
|-----|--------|
| **Providers** | API Provider combo, provider-specific credentials, Check Connection, `claude`/`codex` CLI paths, per-provider model catalog |
| **Roles** | Independent provider + model selection for the **Chat default**, **Wiki**, and **Completions** roles — see [Per-role provider selection](#roles-tab-per-role-provider-selection) |
| **Permissions** | Tool approval mode, Auto-accept Edits |
| **Knowledge Layer** | Wiki librarian, workspace manifest, auto-update-on-drift toggles — see [Settings](#settings) under Knowledge Layer |
| **Profiling** | JFR backend, sampling interval, recording limits — see [Profiling settings](#settings-1) |
| **Advanced** | Token budgets, agent loop/compaction limits, CLI extra args/env script, context collectors, completions tuning |

#### Providers tab

| Setting | Description |
|---------|-------------|
| **API Provider** | Claude: `anthropic` (direct API), `bedrock` (AWS), `vertex` (Google), or `subscription` (Claude account). OpenAI: `OpenAI (ChatGPT subscription)` or `OpenAI API`. Plus `OpenAI-compatible` for imported provider profiles (see [OpenAI-compatible provider profiles](#openai-compatible-provider-profiles)). The provider decides which backend the chat drives — `claude`, `codex`, or the OpenAI-compatible agent loop. |
| **API Key** | Required for `anthropic` provider and inline completions; the `OpenAI API` provider takes an OpenAI API key |
| **Claude CLI path** | Path to `claude` binary (auto-detected from shell PATH); shown only when the selected provider drives Claude |
| **Codex CLI path** | Path to `codex` binary; shown only when the selected provider drives Codex |
| **Models** | Per-provider model catalog table (add/remove rows); the OpenAI-compatible provider's catalog lives in its own card instead — see below |

#### Roles tab: per-role provider selection

Chat, Wiki (the `ask_wiki_librarian` tool and `wiki-author`), and Completions each get an independent **provider + model** picker, so — for example — chat can run on Claude Opus, wiki upkeep on a cheap OpenAI-compatible model, and completions on a third provider entirely. Every picker offers all providers' models, including completion-only ones; the Wiki picker warns inline if you pick a completion-only model, since wiki authoring needs tool support. **Reset defaults** recomputes all three from whichever provider is currently authenticated (latest Opus for chat, latest Haiku for wiki/completions on Claude).

Roles are independent of the Providers tab's global `apiProvider`: the global provider is what a **new** chat defaults to until you override it here; an open chat tab keeps whatever provider/model it started with even if you change these later (see [Session resume](#session-resume) for cross-provider behavior).

#### Permissions tab

| Setting | Description |
|---------|-------------|
| **Tool approval mode** | `Confirm all` (every tool call asks), `Allow safe` (read-only tools auto-approved), or `Allow all` (silent auto-approve with a chat notice). |
| **Auto-accept Edits** | When on, file changes are applied immediately (still reversible from the chat diff link). When off, each edit opens a diff dialog for review. |

#### Advanced tab

| Setting | Description |
|---------|-------------|
| **Completion / Chat / Action token budget** | Context budget per surface (inline completions, chat, intention actions). |
| **Agent tool-call rounds before checkpoint** / **Agent minutes before checkpoint** | Soft limits (0 = unlimited) that trigger a context-compaction checkpoint in the OpenAI-compatible agent loop on a long-running turn. |
| **Compact context at fraction of budget** | Threshold (0.0–1.0, default 0.8) of the context window at which the OpenAI-compatible backend compacts the conversation instead of overflowing. |
| **CLI extra args** | Additional arguments passed to every `claude` invocation. |
| **CLI env script** | Shell script sourced before launching CLI (for custom PATH, env vars). |
| **Enable PSI semantic context** / **Enable Git context** | Toggle the context-engine collectors used for completions/actions. |
| **Preload skill catalog into system prompt** | Advertises discovered Claude Code skills up front (also gates the OpenAI-compatible Skill tool — see [OpenAI-compatible provider profiles](#openai-compatible-provider-profiles)). |
| **Inject baseline working defaults into system prompt** | Toggle the built-in baseline-defaults preamble. |
| **Use minimal-mode CLI for completions** | When on (default), inline completions append `--bare` to the gateway CLI invocation, skipping hooks/LSP/plugin sync/auto-memory for lower latency. Only takes effect with explicit API-key or Bedrock auth; subscription users are unaffected. |
| **Enable inline completions** / **debounce** / **manual-only** | Completions on/off, debounce delay, and hotkey-only mode (see [Manual (hotkey) trigger](#manual-hotkey-trigger)). |

#### Claude subscription sign-in

1. On the **Providers** tab, set API Provider to **Claude subscription**.
2. Click **Sign in with Claude** and complete the browser authentication flow.
3. Chat, skills, and all CLI features use your subscription.

Inline completions still require a separate API key — set it in the same panel or export `ANTHROPIC_API_KEY`.

### OpenAI (Codex) chat backend

ClawDEA can drive the [OpenAI Codex CLI](https://developers.openai.com/codex/) in the chat panel instead of Claude Code. Codex runs through the **same ClawDEA MCP server**, so it gets the full toolset — the IntelliJ index/search tools, the live debugger, diff-gated edit review (`propose_edit`/`propose_write`), the project primer, and your Claude Code skills.

1. Install the Codex CLI: `npm install -g @openai/codex`.
2. On the **Providers** tab of **Settings → Tools → ClawDEA**, set **API Provider** to one of:
   - **OpenAI (ChatGPT subscription)** — sign in with your ChatGPT account. Click **Sign in with ChatGPT** on the settings card, or run `/login` in the chat; ClawDEA shells out to `codex login`, which opens your browser to complete authentication.
   - **OpenAI API** — authenticate with an OpenAI API key.
3. Open (or start) a chat. The model dropdown now lists your Codex/GPT models; ClawDEA routes the conversation to `codex` automatically. The assistant label, the "… is thinking" status, and the per-turn footer all reflect the Codex backend.

Codex runs over its native **app-server** protocol, which unlocks a few things beyond plain streaming:

- **Streamed reasoning.** The model's reasoning is streamed live into a collapsible **Thinking** block above the answer, then removed when the turn ends.
- **Shell & patch approvals through ClawDEA's gate.** Codex's own shell commands and file patches are routed through the same permission system as Claude's tools — the same inline Allow / Always allow / Deny card, the same `permissions.allow`/`permissions.deny` precedence, and the same diff dialog for edits (see [Tool permissions](#tool-permissions)).
- **Native interrupt & mid-turn steering.** **Esc** interrupts the live turn natively; sending a message while Codex is working injects it into the running turn (see [Mid-turn steering](#mid-turn-steering-codex)).

**Switching backends** is just a matter of changing the provider. If the switch flips the backend type (Codex ⇄ Claude), ClawDEA rebuilds the session on the correct CLI so the model dropdown, the assistant label, and the carried-over conversation stay in sync — no stale "Codex" label under a Claude dropdown. Sessions started with either backend show up in `/resume` (see [Session resume](#session-resume)).

**What's Claude-only for now:** inline completions and intention actions still use Claude. OpenAI support is scoped to the chat panel. Claude has no mid-turn steer primitive, so a message sent while Claude is working queues for the next turn instead of injecting into the current one.

**Cost Control** for the ChatGPT subscription shows your real remaining credits and rate-limit windows, reported by the app-server, reusing the same gauge as Claude. (An API-key OpenAI provider is billed per turn and shows a dollar figure as usual.)

### OpenAI-compatible provider profiles

Organizations can distribute custom provider profiles that define model catalogs, credential flows, and pricing for OpenAI-compatible APIs. This section covers *using* a profile once you have one — importing, credentials, exporting, and agentic chat. For the JSON schema itself and how to build a profile from scratch (every field, the credential-flow HTTP-step format, model capability rules, pricing), see **[OpenAI-Compatible Provider Profiles](openai-compatible-profiles.md)**.

ClawDEA supports **two kinds** of profiles:

- **Template profiles** — contain the API base URL, model catalog, credential schema (including declarative credential exchange flows), and pricing, but NO pre-filled credentials. Users must supply their own credentials when importing.
- **Configured profiles** — like templates, but with credentials already supplied by the organization (typically via a secure onboarding flow external to ClawDEA). The profile JSON never contains secrets directly; credentials are encrypted and loaded into the IDE's PasswordSafe on import.

#### Importing a profile

1. Obtain the profile JSON from your organization (via email, internal portal, or a secure onboarding tool).
2. Open **Settings → Tools → ClawDEA → Providers** tab, set **API Provider** to `OpenAI-compatible`, and find the OpenAI-compatible card.
3. Click **Import Profile**.
4. Select the profile JSON file.
5. **Review the import preview** — a dialog shows the profile's name, description, resolved hosts, credential inputs it will ask for, and any declared settings. This is your opportunity to confirm the profile matches what you expect before anything is trusted.
6. If a resolved endpoint is plain HTTP on a non-localhost host, import is rejected outright — that's not a warning you can dismiss. Plain HTTP on `localhost`/`127.0.0.1` prompts for explicit confirmation instead.
7. **Confirm** to import. The profile is now listed in the profile dropdown on the OpenAI-compatible card.

#### Credential handling

- Click **Connect** on the OpenAI-compatible card to run the profile's credential flow: a dialog prompts for whatever the profile declares (a plain API key, or an account/password pair for a login-style exchange), then ClawDEA runs any declarative HTTP steps the profile defines and stores the resulting durable credential in the IDE's native PasswordSafe. Credentials are **never written to settings files or disk** outside the PasswordSafe, and secret inputs are zeroed in memory as soon as the flow finishes. If a step fails, the error dialog names the exact step and HTTP status so you can tell a bad credential from a server-side problem.
- If Connect fails for a reason that isn't your credential (e.g. a server 500) and you already have a valid key some other way, use **Set API Key Manually…** to paste it directly into PasswordSafe, bypassing the flow entirely.
- See [Credential flow](openai-compatible-profiles.md#credential-flow-getting-the-durable-credential) in the profile-building guide for exactly what a profile can declare here.
- **HTTPS requirement**: by default, ClawDEA rejects plain-HTTP base URLs unless they point to `localhost` or `127.0.0.1`. This prevents accidental credential leakage over unencrypted connections. Plain-HTTP localhost is allowed for local testing with explicit confirmation.

#### Exporting a profile

Two separate buttons on the settings card cover the two export shapes:

- **Export Template** — strips all credentials and user-specific configuration. Safe to share with teammates or commit to source control. Recipients must supply their own credentials.
- **Export Configured** — like a template, but with credential *schema* and *exchange flows* intact, plus any non-secret settings values you filled in. Still NO secrets in the JSON; credentials remain in your PasswordSafe.

#### Using the provider

1. Import and confirm the profile (see above).
2. On the **Providers** tab, select the profile from the profile dropdown on the OpenAI-compatible card, then set **API Provider** to `OpenAI-compatible` if it isn't already.
3. Click **Refresh Models** to fetch the catalog, then select a model in the Roles tab (or the Providers tab's model table). The catalog shows each model's resolved capability (agentic, completion-only, etc.).
4. **Inline completions and intention actions** now route to the selected profile and model when the Completions role points at it. The gateway sends requests to the profile's API base URL using the credentials from PasswordSafe.
5. **Model capability gating**: capability is resolved conservatively — an explicit user override wins, then the endpoint's declared capability, then the profile's model rules; anything unresolved defaults to **completion-only**. Only models resolved as **agentic** can start agentic chat. Unknown and completion-only models cannot.
6. **Pricing**: per-model pricing estimates (cost per million input/output/cached/reasoning tokens) are defined in the profile. The cost panel shows usage based on these estimates; this is NOT billing data, just a configured rate card.

#### Verifying tool support

For a model whose capability you're unsure of, use **Verify Tool Support** on the settings card. Select the model row, then click the button: ClawDEA sends a single request carrying one harmless no-op probe function (and no project tools) and reports the outcome.

- The model calls the probe function with valid JSON arguments → marked **agentic** (can start agentic chat).
- The model only replies with text, calls a different function, or sends malformed arguments → treated as **completion-only**.
- The request fails → **unknown** (never silently promoted to agentic).

Verification is **always explicit** — it runs only when you click the button, so it never incurs hidden token usage.

#### Agentic chat

When the selected model is agentic-capable, the chat panel drives it as a full agent over the OpenAI-compatible Chat Completions API:

- **Streamed text and reasoning** — assistant text streams live; a model's reasoning (when the provider emits it) appears in the collapsible **Thinking** block, which is removed once the turn ends.
- **Tools** — the model can call ClawDEA's MCP tools (index, debugger, edit review, primer, skills), a **Skill tool** to invoke discovered Claude Code skills (advertised when **Preload skill catalog into system prompt** is on and skills exist), an **Agent tool** to dispatch sub-agents, plus a permission-gated host shell and reviewed file edits. Every tool call flows through the same approval card and diff review as Claude/Codex, and each completed tool call executes exactly once (resumed sessions never re-run a completed call). Sub-agent steps render flat in the transcript rather than in the collapsible cards Claude/Codex sub-agents get — the parser doesn't track a parent tool-use ID for this backend.
- **Context window management** — the effective context window is resolved from the profile's `contextWindows` map, then the model catalog entry, then a 128K default. Long turns checkpoint and compact once usage crosses the **Compact context at fraction of budget** threshold (Advanced tab), or after the configured tool-call-round/elapsed-minute limits.
- **Cancel-and-continue steering** — send a message while the model is working. ClawDEA cancels the in-flight round, preserves the valid assistant text produced so far, discards any incomplete tool-call fragments, and continues the turn with your new guidance.
- **Errors and retries** — a 401/403 triggers a single credential-renewal prompt; a 429 waits for the `Retry-After` window; a pre-output connection or 5xx failure retries with backoff; a failure *after* partial output asks you before retrying (completed tool calls are reused, not re-run).
- **Sessions** — each conversation is written to a per-profile session ledger under your ClawDEA data directory (never the repo). It appears in `/resume` labeled by provider; resuming the same profile restores full tool state.
- **Cost** — the per-turn footer and cost panel estimate spend from the profile's configured per-token pricing.

#### Explicit provider fallback

ClawDEA never silently switches a conversation to a different provider on a remote error. If you resume or continue a conversation under a *different* provider than the one that produced it (a "fallback" involving the OpenAI-compatible backend), a confirmation dialog names the source and target providers and states that plain user/assistant text is carried over as context while tool-protocol state (tool calls, results, reasoning) is dropped. The transcript is replayed under the alternate provider only if you confirm.

#### Profile isolation

Each profile is identified by a stable **profile ID** (a JSON string field). ClawDEA uses this ID to isolate:

- **Credentials** — stored in PasswordSafe under a profile-scoped key.
- **Model catalog** — each profile's models appear only when that profile is selected.
- **Pricing** — per-profile rate cards; switching profiles switches the pricing basis.
- **Settings** — selected profile and model are persisted to IDE settings (but NOT credentials).

**Organizations distribute profiles separately** from ClawDEA. The plugin includes no pre-configured private provider profiles in its artifact; all provider data is imported at runtime.

---

## Chat Panel

Open from **Tools → Toggle ClawDEA Chat**, or assign a shortcut in **Settings → Keymap** (search for "ClawDEA"). This is the primary interface.

### Sending messages

Type in the input area and press **Enter** to send. Claude streams its response with full Markdown rendering, syntax-highlighted code blocks, and tool-use cards.

### @ mentions

Type `@` to open an inline autocomplete listing your open editor tabs followed by recently git-modified files. Keep typing to filter — the substring matches against `FilenameIndex.getAllFilenames`, so `@metric` finds `WcmMetrics.kt`. Press the up/down arrows to navigate, Enter or Tab to insert.

For a fuller picker, type `@` and then **Tab**. This opens a dialog with two grouped sections — **Files** (filename substring search) and **Symbols** (class and method names via `PsiShortNamesCache`). Inserted mentions become inline tokens in your message that ClawDEA expands into project-relative file paths before sending.

### Turn control

- **Esc** (first press) — Pause the current response. Claude finishes its current sentence and waits; Codex interrupts the live turn natively (`turn/interrupt`).
- **Esc** (second press while paused) — Abort the response entirely.
- Type while paused to send follow-up instructions, then press Enter to resume with your message.

### Mid-turn steering (Codex)

With the Codex backend, you don't have to wait for a turn to finish to redirect it. Send a message while Codex is still working and it's injected into the running turn via native `turn/steer` — the model folds your guidance into what it's already doing instead of restarting. The message appears in the transcript and the "Thinking" indicator stays live. Claude has no steer primitive, so a message sent mid-turn queues for the next turn as before.

### Clickable code references

Code symbols in Claude's responses are clickable. Recognized patterns (file paths, class names, method names) navigate directly to the source via IntelliJ indices. Unrecognized terms open **Search Everywhere** (Shift+Shift).

### Edit review

When **Auto-accept Edits** is off, Claude proposes changes via MCP tools that open a native IntelliJ diff dialog. Review the diff and click **Accept** or **Reject**. The CLI pauses until you decide.

If Claude uses built-in Edit/Write tools instead, a fallback layer renders inline Accept/Reject buttons in the chat. Rejecting reverts the file.

### Tool permissions

Tool calls go through ClawDEA's `--permission-prompt-tool` integration. With the Codex backend, Codex's own shell commands and file patches are routed through the same gate (via the app-server's approval requests), so the modes, cards, rule precedence, and diff dialog below apply to both backends. The behavior depends on **Tool approval mode** in Settings:

- **Confirm all** — every tool call (Bash, search, etc.) shows an inline permission card in the chat tab that triggered the call (multi-panel routing — approvals never land on the wrong tab) with **Allow**, **Always allow...**, and **Deny** buttons. The CLI blocks until you click. Best for tight control, e.g. when running unfamiliar prompts.
- **Allow safe** — ClawDEA-trusted read-only operations (file reads and IntelliJ MCP tools) auto-approve silently, and Claude's native auto-mode classifier may also approve routine actions. Calls that need explicit approval still show a fresh permission card.
- **Allow all** — every tool call auto-approves silently. A small notice appears in chat for each auto-approved call so you can see exactly what ran. Best when you trust the prompt entirely.

Before showing a card, ClawDEA reads Claude Code permission rules from `~/.claude/settings.json`, `.claude/settings.json`, and `.claude/settings.local.json`. Matching `permissions.deny` rules block first, matching `permissions.allow` rules allow the call, and unreadable or future-shaped settings fall back to the interactive card instead of failing the chat session. The **Always allow...** action persists your chosen rule to `.claude/settings.local.json`, with scopes for the exact command/input, similar Bash commands, or every call to the tool. Permission-related values in **CLI extra args** such as `--allowedTools`, `--disallowedTools`, `--permission-mode`, `--permission-prompt-tool`, `--setting-sources`, `--settings`, or `--dangerously-skip-permissions` are still ignored so they cannot bypass ClawDEA's interception.

Independent of approval mode: the **Auto-accept Edits** toggle controls whether *edit* tools (`propose_edit`, `propose_write`, etc.) bypass the diff dialog. With it on, edits apply immediately but remain reversible from the chat diff link; with it off, the diff dialog opens regardless of approval mode.

### Session resume

Use `/resume` to pick up a previous session. Conversation history replays in the chat panel. The picker lists sessions from all three backends — Claude, Codex, and OpenAI-compatible profiles — each labeled by its origin. Resuming a session that matches the active backend is a **native resume**; resuming a session from a different backend replays the prior conversation as **context** on your next message, so the thread continues seamlessly across backends. See [Explicit provider fallback](#explicit-provider-fallback) for how this confirmation works when the OpenAI-compatible backend is involved.

---

## Slash Commands

Type `/` in the chat input to see available commands.

### Local commands

| Command | Description                                           |
|---------|-------------------------------------------------------|
| `/stop` | Stop the current Claude response                      |
| `/clear` | Clear the chat panel                                  |
| `/mode` | Switch between Auto, Plan, and Code modes             |
| `/resume` | Resume a previous session                             |
| `/login` | Sign in with a subscription — Claude (`claude`) or, when an OpenAI provider is active, ChatGPT (`codex login`) |
| `/skills` | Browse and invoke Claude Code skills                  |
| `/cc` | Open Claude Code popup (same session as ClawDEA chat) |
| `/refresh-view` | Re-render the chat panel                              |
| `/profile` | Profile a test, run config, or imported recording (`/profile`, `/profile test ...`, `/profile import ...`) |
| `/note` | Append a quick note to `.claude/notes/CURRENT.md` (personal notes layer) |
| `/promote-to-wiki` | Promote a personal note into a shared wiki concept page |
| `/wiki-audit` | Audit the project wiki for stale source-file links |
| `/wiki-gap` | Show clustered wiki probe misses — use before `/refresh-wiki` |
| `/wiki-relocate <repo-relative-path>` | Move the wiki to a new location and commit the path to `.clawdea/config.json` (team mode opt-in) |

### Knowledge-layer commands (CLI-expanded)

These expand an in-plugin prompt template and forward to the CLI, which drives the edits:

| Command | Description |
|---------|-------------|
| `/learn` | Capture a learning from the current session into the project wiki |
| `/seed-wiki` | Bootstrap the project wiki (`.clawdea/wiki/` by default) with an index and initial concept pages |
| `/refresh-wiki [--status|--apply-low-risk]` | Review and refresh wiki drift via the bundled `wiki-author` agent (tiered by the Roles tab's Wiki provider) |
| `/seed-workspace` | Create a `.clawdea-workspace.md` manifest for cross-repo navigation |

### CLI-forwarded commands

These are sent to the Claude Code CLI for processing:

| Command | Description |
|---------|-------------|
| `/cost` | Show token usage and cost for the current session |
| `/compact` | Compact conversation history to save context |
| `/context` | Show current context window usage |
| `/init` | Initialize a CLAUDE.md file for the current project |

### Index query commands

These use IntelliJ's code indices directly, without sending a message to Claude:

| Command | Description |
|---------|-------------|
| `/callers` | Find all call sites of the method at the caret |
| `/implementations` | Find implementations of the interface/class at the caret |
| `/usages` | Find all references to the symbol at the caret |
| `/supertypes` | Find parent classes and interfaces |

### Skill commands

Skills discovered from `~/.claude/` directories and installed plugins appear as additional `/skill-name` commands. Use `/skills` to browse them.

---

## Intention Actions

Select code and press **Alt+Enter** (or right-click → **ClawDEA**) to access:

| Action | Description |
|--------|-------------|
| **Explain Code** | Get an explanation of the selected code |
| **Optimize Code** | Suggest performance or readability improvements |
| **Generate Test** | Generate unit tests for the selected code |
| **Security Check** | Analyze the selection for security vulnerabilities |
| **Add Documentation** | Generate documentation comments |
| **Refactor with Instructions** | Refactor with a custom prompt |
| **Ask Claude** | Open-ended question about the selection |
| **Fix with Claude** | Fix a bug or issue in the selection |

Works with Claude or a selected OpenAI-compatible provider profile; not available with Codex.

---

## Inline Completions

Tab-completions appear as you type, using editor context (open files, imports, recent edits) gathered by the context engine. They run against whichever provider the **Completions** role points at (Roles tab) — the Claude API, or an OpenAI-compatible provider profile once a profile and model are selected.

The Claude path requires an **Anthropic API key** — set in Settings or via `ANTHROPIC_API_KEY` environment variable. Works alongside Claude subscription for chat.

Configure debounce delay and token budget in the Advanced tab.

### Manual (hotkey) trigger

If you'd rather not spend API tokens on incidental typing, enable **Only request completions on hotkey** in Settings. Automatic as-you-type completions are then suppressed and a suggestion is requested only when you invoke **Trigger Inline Completion** (default **Alt+\\**, ⌥\\ on macOS). The action always works — even with automatic completions on — so you can force a fresh suggestion on demand. Rebind the shortcut in **Settings → Keymap → "Trigger Inline Completion"**.

---

## MCP Tools

ClawDEA runs a local MCP server that gives Claude direct access to IntelliJ's indices and IDE features. These tools are used automatically — you don't invoke them directly.

### Code index tools

| Tool | What it does |
|------|-------------|
| `find_files` | Search files by name pattern or glob |
| `find_usages` | Find all references to a symbol |
| `find_callers` | Find call sites of a method |
| `find_implementations` | Find classes implementing an interface |
| `find_supertypes` | Find parent classes and interfaces |
| `find_related_types` | Get signatures of imported project types |
| `search_text` | Literal or regex content search across project sources |

### IDE tools

| Tool | What it does |
|------|-------------|
| `resolve_symbol` | Go to definition of a symbol |
| `get_diagnostics` | Get compiler errors and warnings |
| `get_project_context` | Get full context for a file and position |
| `get_primer` | Return the assembled project primer (CLAUDE.md + module map + focus) |

### Knowledge-layer tools

| Tool | What it does |
|------|-------------|
| `read_wiki_page` | Read a concept, source, or index page from the project wiki |
| `ask_wiki_librarian` | Answer a project-design question via the librarian, tiered by the Wiki role's provider (registered when **Enable wiki librarian** is on). Exempt from the 60s MCP tool timeout. See [Wiki librarian](#wiki-librarian). |
| `search_wiki` | Search the project wiki (registered only when **Enable wiki librarian** is off — otherwise `ask_wiki_librarian` owns wiki access). Accepts an optional `pathTokens` array (e.g. `["policies", "clientlibs"]`) matched against file names and headings. Low-hit probes are recorded for `/wiki-gap`. |
| `record_wiki_suggestion` | Allow-listed for the librarian's Claude and OpenAI-compatible execution paths — records a `missingConcept` / `staleConcept` / `incompleteConcept` suggestion that surfaces at refresh time |
| `list_workspace_repos` | List sibling repos from `.clawdea-workspace.md` |
| `read_sibling_wiki` | Read a wiki page from a sibling repo |
| `read_sibling_repo_state` | Read `REPO_STATE.md` from a sibling repo |

### Edit review tools

| Tool | What it does |
|------|-------------|
| `propose_edit` | Propose a file edit with diff review |
| `propose_write` | Propose writing a new file |
| `propose_multi_edit` | Propose multiple sequential edits to a single file in one diff dialog |
| `propose_notebook_edit` | Propose an edit to a Jupyter notebook cell |

### Profiling tools

| Tool | What it does |
|------|-------------|
| `profiling_start` | Start a profiling session against a run config, test FQN, or PID |
| `profiling_stop` | Stop an active profiling session |
| `profiling_status` | Query the state of a session (non-blocking) |
| `profiling_list` | List available recordings with metadata |
| `profiling_import` | Import a `.jfr` or `.hprof` file for analysis |
| `profiling_analyze_cpu` | Analyze CPU hotspots in a recording |
| `profiling_analyze_allocations` | Analyze allocation hotspots in a recording |
| `profiling_analyze_leaks` | Analyze memory leaks in a `.hprof` heap dump |

---

## Knowledge Layer

ClawDEA builds a project-local knowledge base under `.claude/` so Claude starts each turn already oriented, instead of re-deriving context by grepping.

### Primer

The **primer** assembles `CLAUDE.md` + the auto-generated `.clawdea/REPO_STATE.md` (current branch, hot files, recent commits) + the wiki's `index.md` table of contents, and ships it with every turn. Claude can also re-fetch it on demand via the `get_primer` MCP tool.

### Wiki (`.clawdea/wiki/`)

Concept pages live under `.clawdea/wiki/concepts/` (default mode; team mode relocates the wiki to a committed path such as `docs/llm-wiki/`). Each page names the files, classes, and entry points for a subsystem — Claude reads a concept page first to orient, then navigates directly instead of broad text search. Seed a fresh wiki with `/seed-wiki`, refresh auto-generated parts with `/refresh-wiki`, and audit for stale links with `/wiki-audit`. Capture a learning mid-session with `/learn`.

**Page schemas.** `/seed-wiki` and `/learn` classify each concept as `pipeline`, `runtime-behavior`, or `navigation`:

- **Pipeline / runtime-behavior** pages use the invariant-first template (purpose → invariants → resolution pipeline → anti-patterns → source pointers) — designed so an LLM reasoning about runtime has an authoritative anchor, not just file pointers.
- **Navigation** pages keep the lighter summary + entry-point schema, still useful for flat subsystems.

The templates live in `src/main/resources/prompts/wiki-page-invariant.md` and `wiki-page-navigation.md`, iterable without a Kotlin rebuild.

**Probe-miss capture and `/wiki-gap`.** When `search_wiki` returns low-hit results for a non-trivial query, the miss is recorded in the wiki's `.drift-state.json`. `/wiki-gap` clusters recent misses by path-token Jaccard similarity and suggests concept-page slugs, giving `/refresh-wiki` a concrete work queue.

**Correction capture.** When you follow an assistant message with a correction ("no, actually the policy is inert because…"), ClawDEA detects it heuristically, records a `USER_CORRECTION` evidence signal, and surfaces a `/learn <auto-drafted-topic>` suggestion in chat.

### Wiki team mode

By default, the wiki lives at `.clawdea/wiki/` and each developer's drift state is local to their working copy. Teams can opt into a **shared wiki** committed to git:

- Run `/wiki-relocate docs/llm-wiki` (or any repo-relative path). ClawDEA writes `.clawdea/config.json` with `{"wikiPath": "docs/llm-wiki"}`, moves any existing wiki contents (preserving git history via `git mv` for tracked files), and adds `.clawdea/wiki-state.local.json` to `.gitignore`.
- Commit `.clawdea/config.json` and the new wiki path to share with the team. Teammates auto-discover team mode on next project open — no manual setup.

In team mode the drift state splits into:

| File | Tracked in git? | Holds |
|------|-----------------|-------|
| `<wikiDir>/.wiki-state.json` | Yes (team-shared) | `lastSyncedCommit` (the git SHA the wiki currently describes) and open librarian `suggestions` |
| `.clawdea/wiki-state.local.json` | No (gitignored) | Per-user fields: `lastScanAt`, `dismissed`, `probeMisses`, `userCorrections` |

`lastSyncedCommit` anchors commit-driven drift detection: `CommitWikiDriftDetector` only considers commits in `lastSyncedCommit..HEAD`, and the SHA bumps to HEAD after every drift cycle. Branch switching is automatic because the team file is git-tracked.

To revert: delete `.clawdea/config.json` (and optionally move the wiki back to `.clawdea/wiki/`). The next project open returns to default mode.

### Commit-driven wiki maintenance

ClawDEA watches the project's git refs (commit, fetch, pull, branch switch). On any change, `CommitWikiDriftDetector` reads commits since the last drift rescan and flags any concept page (e.g. `concepts/*.md` under the wiki) whose mentioned files or class names appear in the touched paths. Each flagged page becomes a `CommitDrift` drift event.

**Orphan-subsystem detection.** `CommitWikiDriftDetector` and the rename detector can only flag pages that already mention something — they're blind to a brand-new subsystem no page mentions at all (e.g. a batch of same-prefix classes dropped into an existing package). `OrphanCodeDetector` closes that gap: it clusters declared source types by their leading PascalCase word, and for any cluster of 3+ types where the wiki mentions neither a class name nor the shared prefix word (as a whole word, anywhere in prose), it emits an `OrphanSubsystem` drift event.

If **Auto-update wiki on drift** is enabled, ClawDEA runs the bundled `wiki-author` agent to draft the page edits — `propose_write` / `propose_edit` calls open diff dialogs (or apply silently if **Auto-accept edits** is also enabled). A one-line note in any active chat reports the outcome. Like the librarian, `wiki-author` is tiered by the Roles tab's Wiki provider: a Claude-family Wiki role runs it as a `claude -p --agents` subprocess (unchanged from earlier releases); an OpenAI-compatible Wiki role runs it through the agentic tool loop instead (no `--agents`); a Codex Wiki role is not yet supported for authoring (read-only librarian Q&A only).

If **Auto-update wiki on drift** is disabled, the drift banner shows the events; clicking `/refresh-wiki to review` hands the digest to the same `wiki-author` agent inline (visible as a `Task` tool-use in the chat, on the Claude-family path).

**Drift event icons.** The drift banner and `wiki-author` digest mark each event with a per-kind icon so different drift causes never visually conflate:

- 🔗 stale link (broken file/symbol reference in a wiki page)
- 📋 stale manifest (workspace manifest entry pointing at a missing repo)
- ↻ code changed (commit-driven drift — a wiki page mentions paths touched in `lastSyncedCommit..HEAD`)
- 🌱 undocumented subsystem (orphan-subsystem detection — a new area with no wiki mention at all)
- ✍ suggested update (proposed by the wiki librarian while answering a question)

Auto-applied fixes and `/refresh-wiki` summaries carry the same icons. Wiki MCP tool calls in chat also use distinct icons: 📚 for reads (`read_wiki_page`, `search_wiki`, `read_sibling_wiki`) and 📝 for writes (`record_wiki_suggestion`) and edits whose `file_path` resolves under the wiki directory.

### Wiki librarian

For any non-trivial question about this project's design, ClawDEA's main chat calls the `ask_wiki_librarian` MCP tool rather than running a keyword search itself. The librarian reads the project wiki in its own fresh LLM context every call, verifies claims against current source, and returns a synthesised answer with citations. Wiki content never enters the main chat's context, so it doesn't decay across long conversations.

**Execution is tiered by the Roles tab's Wiki provider** (see [Per-role provider selection](#roles-tab-per-role-provider-selection)), not by whichever provider the chat itself is using:

| Wiki role provider | How it runs |
|---|---|
| Claude-family (`anthropic` / `bedrock` / `vertex` / `subscription`) | A headless `claude -p` subprocess, authenticated as the Wiki role, read-only via an `--allowedTools` allowlist under `--permission-mode bypassPermissions` |
| OpenAI-compatible | An in-process agentic tool loop on the Wiki role's profile, with a read-only tool allowlist |
| Codex (`openai` / `openai-subscription`) | A `codex exec --json` subprocess under a `read-only` sandbox with approvals disabled and **no MCP server** — a real macOS sandbox blocks the loopback MCP socket, so this path reads the on-disk wiki and greps the tree with Codex's own shell instead. Trades away `record_wiki_suggestion` gap-logging and the IntelliJ index tools for that guarantee. |

There is no `--agents` subagent injection for the main chat anymore — `ask_wiki_librarian` is the single entry point regardless of which backend the chat itself is on, which also sidesteps a real bug the old approach had on Windows (the injected agent definition could exceed `cmd.exe`'s command-line length cap). The tool is exempt from ClawDEA's normal 60-second MCP tool timeout, since a librarian answer can take longer.

**When the librarian finds a wiki gap** while answering a question — a real subsystem with no page, a stale claim contradicted by current source, or a covered concept missing a relevant aspect — it logs a suggestion via the `record_wiki_suggestion` MCP tool (Claude and OpenAI-compatible paths only; unavailable on the Codex path). Suggestions accumulate in the wiki's drift-state file alongside other drift events and surface through the existing flow:

- With **Auto-update wiki on drift** enabled, suggestions surface alongside other drift events when the commit-driven detector fires.
- Without it, suggestions wait until `/refresh-wiki` is invoked.

The librarian never writes wiki files directly. Authoring stays user-initiated: review the suggestion, decide yes/no, and either dismiss it or draft the wiki change through the main chat. (Drafting itself — the `wiki-author` subagent behind `/refresh-wiki` and auto-apply — is a separate mechanism from the librarian; see [Commit-driven wiki maintenance](#commit-driven-wiki-maintenance).)

**Opt out** by clearing **Enable wiki librarian** in plugin settings. This restores the legacy "search_wiki probe" directive in the primer, re-registers `search_wiki` as an MCP tool, and stops registering `ask_wiki_librarian`.

### Personal notes (`.claude/notes/CURRENT.md`)

A per-user scratchpad outside the shared wiki. Append with `/note <text>` from the chat input. When a note becomes broadly useful, run `/promote-to-wiki` to convert it into a concept page.

### Workspace manifest (`.clawdea-workspace.md`)

For multi-repo work, `/seed-workspace` creates a manifest listing sibling repos by key, filesystem path, and role. Claude can then read sibling wikis and repo state without leaving the current project, using `list_workspace_repos`, `read_sibling_wiki`, and `read_sibling_repo_state`. `DriftDetector` opportunistically flags manifest entries whose paths no longer exist and wiki source-file links pointing at renamed/removed files.

### Settings

**Settings → Tools → ClawDEA → Knowledge layer** exposes the knowledge, workspace, and drift controls:

- **Enable knowledge layer** — main switch. When off, ClawDEA stops assembling MAP/wiki/notes/workspace into the primer and disables the related MCP tools.
- **Enable wiki librarian** — on by default. The primer and backend-specific system prompt direct the main agent to call the `ask_wiki_librarian` MCP tool for design questions, and `search_wiki` is not registered. When off, the legacy "first call must be a `search_wiki` probe" directive is emitted and `search_wiki` returns. See [Wiki librarian](#wiki-librarian).
- **Enable workspace manifest** — read sibling repos from `.clawdea-workspace.md` and surface them via `list_workspace_repos` / `read_sibling_*`.
- **Auto-update wiki on drift** — when on, high-confidence drift fixes (single-match code renames, manifest comment-outs) apply silently and the commit-driven detector hands `CommitDrift` events to `wiki-author` for unattended drafting (tiered by the Roles tab's Wiki provider — see [Wiki librarian](#wiki-librarian)). When off, every change goes through diff review.

---

## Debugger Integration

ClawDEA exposes 21 debug tools that let Claude drive IntelliJ's debugger. Claude can launch sessions, set breakpoints, step through code, and inspect variables — useful for investigating bugs where runtime state matters more than static code reading.

### Session lifecycle

| Tool | What it does |
|------|-------------|
| `debug_launch` | Launch a debug session from an existing Run/Debug configuration |
| `debug_launch_adhoc` | Launch an ad-hoc session for a Java class or JUnit test |
| `debug_attach` | Attach to a running process (Java JDWP or Node.js) |
| `debug_get_session` | Check session status — type, suspended state, current position |
| `debug_stop` | Stop the session and clean up all Claude-managed breakpoints |

### Breakpoints

| Tool | What it does |
|------|-------------|
| `debug_set_breakpoint` | Set a line breakpoint with optional condition or log expression |
| `debug_remove_breakpoint` | Remove a Claude-created breakpoint |
| `debug_disable_breakpoint` | Temporarily disable any breakpoint |
| `debug_enable_breakpoint` | Re-enable a disabled breakpoint |
| `debug_list_breakpoints` | List all breakpoints with ownership info |

**Breakpoint ownership:** Claude tracks which breakpoints it created vs. which belong to you. Claude can only remove its own breakpoints — your breakpoints can be disabled but never deleted. When Claude "borrows" one of your breakpoints (e.g., to add a condition), it restores the original state when done.

### Execution control

| Tool | What it does |
|------|-------------|
| `debug_resume` | Resume execution until next breakpoint |
| `debug_pause` | Pause a running program |
| `debug_step_over` | Step over the current line |
| `debug_step_into` | Step into the current method call |
| `debug_step_out` | Step out of the current method |
| `debug_run_to_cursor` | Run to a specific file and line |

### Inspection

| Tool | What it does |
|------|-------------|
| `debug_get_frames` | Get the current call stack |
| `debug_get_variables` | Get local variables in a stack frame |
| `debug_expand_variable` | Expand an object to see its fields (dot-path syntax) |
| `debug_evaluate` | Evaluate an arbitrary expression in the current context |
| `debug_set_value` | Modify a variable's value at runtime |

### Example workflow

Ask Claude to investigate a bug:

> "The `processOrder` method returns null when the discount is negative. Set a breakpoint at OrderService.java line 42, run the test `OrderServiceTest`, and check what `discount` equals when it hits."

Claude will:
1. Set a breakpoint at the specified location
2. Launch the test in debug mode
3. Wait for the breakpoint to hit
4. Inspect the `discount` variable
5. Report findings and suggest a fix

---

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| **Enter** | Send message |
| **Esc** | Pause / Abort response |
| **Alt+Enter** | Intention actions menu (with code selected) |
| **Tab** | Accept inline completion |

Toggle Chat and New Chat Session are available in **Tools → ClawDEA** but ship without default keybindings to avoid conflicts. Assign your own in **Settings → Keymap** (search for "ClawDEA").

---

## Profiling

ClawDEA can profile JVM applications using JDK Flight Recorder, analyze the results, and propose source-level fixes — all driven by Claude.

### Entry points

| Entry point | How to use |
|-------------|-----------|
| **`/profile` command** | Type `/profile` in the chat panel. Claude will ask what to profile (test, run config, PID, or import a file). |
| **`/profile test com.example.FooTest#testBar`** | Directly profile a specific test method. |
| **`/profile import /path/to/file.jfr`** | Import and analyze an existing recording. |
| **Gutter icon** | Click the CPU-flame icon next to any `@Test` method to profile it. |

### How it works

1. ClawDEA creates a JUnit run configuration with JFR JVM arguments injected.
2. The test runs in IntelliJ's Run tool window with Flight Recording active.
3. When the process exits, the `.jfr` file is imported and parsed (CPU samples + allocations).
4. Claude analyzes hotspots and proposes fixes via `propose_edit`.

### Settings

Configure under **Settings → Tools → ClawDEA → Profiling**:

| Setting | Default | Description |
|---------|---------|-------------|
| Backend | Auto | `Auto` (detects IntelliJ profiler), `IntelliJ Profiler`, or `JFR` |
| Sampling interval | 10 ms | CPU sampling frequency |
| Max duration | 900 s | Recording auto-stops after this |
| Max recording size | 500 MB | Prevents runaway disk usage |
| Stack depth | 128 | Max frames captured per sample |
| Max stored recordings | 20 | Older recordings are evicted |
| Max storage | 5 GB | Total disk budget for recordings |
| Auto-analyze | On | Claude analyzes immediately after capture |
| Top-N hotspots | 50 | Number of hotspots reported |

### Requirements

- JDK 11+ on the profiled process (JFR is not available in OpenJDK 8)
- For heap dump analysis (`.hprof`): the shark extension is downloaded on first use (~1 MB)

---

## Troubleshooting

### Claude CLI not found

ClawDEA auto-detects the `claude` binary from your shell PATH. If IntelliJ was launched from Finder/Dock (not terminal), PATH may not include npm global binaries. Fix by either:
- Setting **Claude CLI path** on the **Providers** tab to the full path (e.g., `/usr/local/bin/claude`)
- Setting **CLI env script** on the **Advanced** tab to a script that sources your shell profile
- Launching IntelliJ from terminal: `open -a "IntelliJ IDEA"`

### OpenAI Codex CLI not found

Same PATH caveat as above, for the `codex` binary. Install it with `npm install -g @openai/codex`, then set **Codex CLI path** on the **Providers** tab to the full path if auto-detection misses it. If chat still routes to Claude after selecting an OpenAI provider, confirm you're signed in — run `/login` (ChatGPT) or set an OpenAI API key.

### "Only one instance of IDEA can be run at a time"

This happens when building with `buildSearchableOptions` while IntelliJ is running. Use:
```
./gradlew build -x buildSearchableOptions
```

### Edit diff dialog not appearing

Ensure **Auto-accept Edits** is off on the **Permissions** tab. The diff dialog only opens when Claude uses `propose_edit`/`propose_write` MCP tools.

### Inline completions not working

If the Completions role (Roles tab) points at Claude, verify an Anthropic API key is set — subscription auth alone doesn't cover completions. If it points at an OpenAI-compatible profile, confirm that profile has a selected model. Either way, check **Enable inline completions** is on in the Advanced tab.

---

## How ClawDEA stays in sync with Claude Code

ClawDEA is a thin layer over the `claude` CLI binary. When Anthropic ships a new flag, an event type, or an MCP config field, ClawDEA needs to know — either to adopt the improvement, or to keep working at all.

The repo runs an automated drift monitor:

- **Weekly digest.** A scheduled GitHub Action diffs `claude --help`, npm version, and selected docs pages each Monday morning UTC. New surfaces matching a watchlist regex automatically file a tracking issue.
- **PR-time tripwire.** Every pull request runs `CliFixtureReplayTest`, which replays a recorded `claude -p` transcript through ClawDEA's parser. A new event type or renamed field upstream shows up as a red CI run with the exact offending event annotated.

Most users won't notice this — it only matters when ClawDEA's behavior diverges from the latest Claude Code, in which case a maintainer will be adopting the change. If you'd like to follow along, watch [issue #11](https://github.com/adobe/ClawDEA/issues/11) (the umbrella for drift work) or read the [drift monitoring guide](drift-monitoring.md).
