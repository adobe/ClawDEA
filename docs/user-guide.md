# ClawDEA User Guide

## Getting Started

### Prerequisites

- **IntelliJ IDEA 2026.1** or later (Community or Ultimate)
- **Java 21** runtime
- **Claude Code CLI** — install with `npm install -g @anthropic-ai/claude-code`
- One of: Anthropic API key, Claude Pro/Max/Team/Enterprise subscription, AWS Bedrock credentials, or Google Vertex credentials

### Installation

1. Download `ClawDEA-<version>.zip` from the [latest release](https://github.com/adobe/ClawDEA/releases/latest).
2. In IntelliJ: **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the zip and restart the IDE.

### Configuration

Open **Settings → Tools → ClawDEA** to configure:

| Setting | Description |
|---------|-------------|
| **API Provider** | `anthropic` (direct API), `bedrock` (AWS), `vertex` (Google), or `subscription` (Claude account) |
| **API Key** | Required for `anthropic` provider and inline completions |
| **CLI Path** | Path to `claude` binary (auto-detected from shell PATH) |
| **Auto-accept Edits** | When on, file changes are applied immediately. When off, each edit opens a diff dialog for review. |
| **Tool approval mode** | `Confirm all` (every tool call asks), `Allow safe` (read-only tools auto-approved), or `Allow all` (silent auto-approve with a chat notice). |
| **Default Chat Mode** | `Auto`, `Plan`, or `Code` — sets the initial mode for new conversations. |
| **Use minimal-mode CLI for completions** | When on (default), inline completions append `--bare` to the gateway CLI invocation, skipping hooks/LSP/plugin sync/auto-memory for lower latency. Only takes effect with explicit API-key or Bedrock auth; subscription users are unaffected. |
| **CLI Extra Args** | Additional arguments passed to every `claude` invocation. |
| **CLI Env Script** | Shell script sourced before launching CLI (for custom PATH, env vars). |

#### Claude subscription sign-in

1. Set API Provider to **Claude subscription**.
2. Click **Sign in with Claude** and complete the browser authentication flow.
3. Chat, skills, and all CLI features use your subscription.

Inline completions still require a separate API key — set it in the same panel or export `ANTHROPIC_API_KEY`.

---

## Chat Panel

Open from **Tools → Toggle ClawDEA Chat**, or assign a shortcut in **Settings → Keymap** (search for "ClawDEA"). This is the primary interface.

### Sending messages

Type in the input area and press **Enter** to send. Claude streams its response with full Markdown rendering, syntax-highlighted code blocks, and tool-use cards.

### @ mentions

Type `@` to open an inline autocomplete listing your open editor tabs followed by recently git-modified files. Keep typing to filter — the substring matches against `FilenameIndex.getAllFilenames`, so `@metric` finds `WcmMetrics.kt`. Press the up/down arrows to navigate, Enter or Tab to insert.

For a fuller picker, type `@` and then **Tab**. This opens a dialog with two grouped sections — **Files** (filename substring search) and **Symbols** (class and method names via `PsiShortNamesCache`). Inserted mentions become inline tokens in your message that ClawDEA expands into project-relative file paths before sending.

### Turn control

- **Esc** (first press) — Pause the current response. Claude finishes its current sentence and waits.
- **Esc** (second press while paused) — Abort the response entirely.
- Type while paused to send follow-up instructions, then press Enter to resume with your message.

### Clickable code references

Code symbols in Claude's responses are clickable. Recognized patterns (file paths, class names, method names) navigate directly to the source via IntelliJ indices. Unrecognized terms open **Search Everywhere** (Shift+Shift).

### Edit review

When **Auto-accept Edits** is off, Claude proposes changes via MCP tools that open a native IntelliJ diff dialog. Review the diff and click **Accept** or **Reject**. The CLI pauses until you decide.

If Claude uses built-in Edit/Write tools instead, a fallback layer renders inline Accept/Reject buttons in the chat. Rejecting reverts the file.

### Tool permissions

Tool calls go through ClawDEA's `--permission-prompt-tool` integration. The behavior depends on **Tool approval mode** in Settings:

- **Confirm all** — every tool call (Bash, search, etc.) shows an inline permission card in chat with **Allow**, **Always allow...**, and **Deny** buttons. The CLI blocks until you click. Best for tight control, e.g. when running unfamiliar prompts.
- **Allow safe** — ClawDEA-trusted read-only operations (file reads and IntelliJ MCP tools) auto-approve silently, and Claude's native auto-mode classifier may also approve routine actions. Calls that need explicit approval still show a fresh permission card.
- **Allow all** — every tool call auto-approves silently. A small notice appears in chat for each auto-approved call so you can see exactly what ran. Best when you trust the prompt entirely.

Before showing a card, ClawDEA reads Claude Code permission rules from `~/.claude/settings.json`, `.claude/settings.json`, and `.claude/settings.local.json`. Matching `permissions.deny` rules block first, matching `permissions.allow` rules allow the call, and unreadable or future-shaped settings fall back to the interactive card instead of failing the chat session. The **Always allow...** action persists your chosen rule to `.claude/settings.local.json`, with scopes for the exact command/input, similar Bash commands, or every call to the tool. Permission-related values in **CLI extra args** such as `--allowedTools`, `--disallowedTools`, `--permission-mode`, `--permission-prompt-tool`, `--setting-sources`, `--settings`, or `--dangerously-skip-permissions` are still ignored so they cannot bypass ClawDEA's interception.

Independent of approval mode: the **Auto-accept Edits** toggle controls whether *edit* tools (`propose_edit`, `propose_write`, etc.) bypass the diff dialog. With it on, edits apply immediately but remain reversible from the chat diff link; with it off, the diff dialog opens regardless of approval mode.

### Session resume

Use `/resume` to pick up a previous Claude Code session. Conversation history replays in the chat panel.

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
| `/login` | Sign in with a Claude subscription                    |
| `/skills` | Browse and invoke Claude Code skills                  |
| `/cc` | Open Claude Code popup (same session as ClawDEA chat) |
| `/refresh-view` | Re-render the chat panel                              |
| `/note` | Append a quick note to `.claude/notes/CURRENT.md` (personal notes layer) |
| `/promote-to-wiki` | Promote a personal note into a shared wiki concept page |
| `/wiki-audit` | Audit `.claude/wiki/` for stale source-file links |
| `/wiki-gap` | Show clustered wiki probe misses — use before `/refresh-wiki` |

### Knowledge-layer commands (CLI-expanded)

These expand an in-plugin prompt template and forward to the CLI, which drives the edits:

| Command | Description |
|---------|-------------|
| `/learn` | Capture a learning from the current session into the project wiki |
| `/seed-wiki` | Bootstrap `.claude/wiki/` with an index and initial concept pages |
| `/refresh-wiki [--dream|--status|--apply-low-risk]` | Review and refresh wiki drift, including Dream-backed maintenance suggestions |
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

---

## Inline Completions

Tab-completions powered by the Claude API appear as you type, using editor context (open files, imports, recent edits) gathered by the context engine.

Requires an **Anthropic API key** — set in Settings or via `ANTHROPIC_API_KEY` environment variable. Works alongside Claude subscription for chat.

Configure debounce delay and token budget in Settings.

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
| `read_wiki_page` | Read a concept, source, or index page from `.claude/wiki/` |
| `search_wiki` | Search the project wiki. Accepts an optional `pathTokens` array (e.g. `["policies", "clientlibs"]`) matched against file names and headings. Low-hit probes are recorded for `/wiki-gap`. |
| `list_workspace_repos` | List sibling repos from `.clawdea-workspace.md` |
| `read_sibling_wiki` | Read a wiki page from a sibling repo |
| `read_sibling_repo_state` | Read `REPO_STATE.md` from a sibling repo |

### Edit review tools

| Tool | What it does |
|------|-------------|
| `propose_edit` | Propose a file edit with diff review |
| `propose_write` | Propose writing a new file |
| `propose_multi_edit` | Propose edits to multiple files |

---

## Knowledge Layer

ClawDEA builds a project-local knowledge base under `.claude/` so Claude starts each turn already oriented, instead of re-deriving context by grepping.

### Primer

The **primer** assembles `CLAUDE.md` + the auto-generated `.claude/REPO_STATE.md` (current branch, hot files, recent commits) + the `.claude/wiki/index.md` table of contents, and ships it with every turn. Claude can also re-fetch it on demand via the `get_primer` MCP tool.

### Wiki (`.claude/wiki/`)

Concept pages live under `.claude/wiki/concepts/`. Each page names the files, classes, and entry points for a subsystem — Claude reads a concept page first to orient, then navigates directly instead of broad text search. Seed a fresh wiki with `/seed-wiki`, refresh auto-generated parts with `/refresh-wiki`, and audit for stale links with `/wiki-audit`. Capture a learning mid-session with `/learn`.

**Page schemas.** `/seed-wiki` and `/learn` classify each concept as `pipeline`, `runtime-behavior`, or `navigation`:

- **Pipeline / runtime-behavior** pages use the invariant-first template (purpose → invariants → resolution pipeline → anti-patterns → source pointers) — designed so an LLM reasoning about runtime has an authoritative anchor, not just file pointers.
- **Navigation** pages keep the lighter summary + entry-point schema, still useful for flat subsystems.

The templates live in `src/main/resources/prompts/wiki-page-invariant.md` and `wiki-page-navigation.md`, iterable without a Kotlin rebuild.

**Probe-miss capture and `/wiki-gap`.** When `search_wiki` returns low-hit results for a non-trivial query, the miss is recorded in `.claude/wiki/.drift-state.json`. `/wiki-gap` clusters recent misses by path-token Jaccard similarity and suggests concept-page slugs, giving `/refresh-wiki` a concrete work queue.

**Correction capture.** When you follow an assistant message with a correction ("no, actually the policy is inert because…"), ClawDEA detects it heuristically, records a `USER_CORRECTION` evidence signal, and surfaces a `/learn <auto-drafted-topic>` suggestion in chat. The Dream scorer ranks candidates backed by user corrections ahead of peers.

Dream-backed wiki maintenance can detect low-signal index growth, stale/duplicate concept pages, and old wiki link syntax. Low-risk cleanup, such as high-confidence single-target Dream link normalization when the target concept exists, can apply automatically only when Auto-update wiki on drift is enabled; substantive page creation and rewrites still go through diff review.

### Personal notes (`.claude/notes/CURRENT.md`)

A per-user scratchpad outside the shared wiki. Append with `/note <text>` from the chat input. When a note becomes broadly useful, run `/promote-to-wiki` to convert it into a concept page.

### Workspace manifest (`.clawdea-workspace.md`)

For multi-repo work, `/seed-workspace` creates a manifest listing sibling repos by key, filesystem path, and role. Claude can then read sibling wikis and repo state without leaving the current project, using `list_workspace_repos`, `read_sibling_wiki`, and `read_sibling_repo_state`. `DriftDetector` opportunistically flags manifest entries whose paths no longer exist and wiki source-file links pointing at renamed/removed files.

### Settings

**Settings → Tools → ClawDEA → Knowledge layer** exposes the knowledge, workspace, drift, and Dream maintenance controls:

- **Enable knowledge layer** — main switch. When off, ClawDEA stops assembling MAP/wiki/notes/workspace into the primer and disables the related MCP tools.
- **Enable workspace manifest** — read sibling repos from `.clawdea-workspace.md` and surface them via `list_workspace_repos` / `read_sibling_*`.
- **Auto-update wiki on drift** — when on, high-confidence drift fixes (single-match code renames, manifest comment-outs) apply silently; learn-on-probe-miss writes use `Write`/`Edit` instead of `propose_*`. When off, every change goes through diff review.
- **Enable Dream wiki maintenance** — controls both automatic/default Dream due checks and explicit `/refresh-wiki --dream`. When off, Dream scans do not run.
- **Dream min elapsed (hours)**, **Dream min signal units**, and **Dream scan throttle (minutes)** — tune when automatic/default Dream maintenance becomes due. Manual `/refresh-wiki --dream` bypasses these due gates, but still respects the enable setting, filesystem lock, and active-turn safety.

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
- Setting **CLI Path** in Settings to the full path (e.g., `/usr/local/bin/claude`)
- Setting **CLI Env Script** to a script that sources your shell profile
- Launching IntelliJ from terminal: `open -a "IntelliJ IDEA"`

### "Only one instance of IDEA can be run at a time"

This happens when building with `buildSearchableOptions` while IntelliJ is running. Use:
```
./gradlew build -x buildSearchableOptions
```

### Edit diff dialog not appearing

Ensure **Auto-accept Edits** is off in Settings. The diff dialog only opens when Claude uses `propose_edit`/`propose_write` MCP tools.

### Inline completions not working

Verify that an Anthropic API key is set — subscription auth alone doesn't cover completions. Check that **Completions Enabled** is on in Settings.

---

## How ClawDEA stays in sync with Claude Code

ClawDEA is a thin layer over the `claude` CLI binary. When Anthropic ships a new flag, an event type, or an MCP config field, ClawDEA needs to know — either to adopt the improvement, or to keep working at all.

The repo runs an automated drift monitor:

- **Weekly digest.** A scheduled GitHub Action diffs `claude --help`, npm version, and selected docs pages each Monday morning UTC. New surfaces matching a watchlist regex automatically file a tracking issue.
- **PR-time tripwire.** Every pull request runs `CliFixtureReplayTest`, which replays a recorded `claude -p` transcript through ClawDEA's parser. A new event type or renamed field upstream shows up as a red CI run with the exact offending event annotated.

Most users won't notice this — it only matters when ClawDEA's behavior diverges from the latest Claude Code, in which case a maintainer will be adopting the change. If you'd like to follow along, watch [issue #11](https://github.com/adobe/ClawDEA/issues/11) (the umbrella for drift work) or read the [drift monitoring guide](drift-monitoring.md).
