# ClawDEA

> **[Download latest release](https://github.com/adobe/ClawDEA/releases/latest)** · **[User Guide](docs/user-guide.md)**

Native [Claude Code](https://docs.anthropic.com/en/docs/claude-code) integration for IntelliJ IDEA — uses IDE's code indices, runs diagnostics, proposes edits, and drives the debugger, all without leaving the IDE.
Optionally builds a knowledge base to enhance LLM understanding of current and related projects.

<p align="center">
  <img src="docs/images/debug-demo.gif" alt="ClawDEA driving IntelliJ's debugger — setting breakpoints, stepping through code, inspecting variables, and mutating values at runtime" width="800">
  <br>
  <em>Claude autonomously debugging a Java application — breakpoints, stepping, variable inspection, and runtime mutation</em>
</p>

### Install from release

1. Download `ClawDEA-<version>.zip` from the link above.
2. In IntelliJ: **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the zip and restart.

## Features

**Chat panel** — Streams Claude responses with Markdown, code blocks, tool-use cards, and clickable code references that navigate to source. Open from **Tools → Toggle ClawDEA Chat** (assign your own shortcut in Keymap settings).

**MCP server** — A local server exposes IntelliJ's indices as MCP tools: find files, usages, callers, implementations, supertypes, resolve symbols, read diagnostics, literal/regex content search via `search_text`, profiling (8 tools), and cross-project navigation via `list_workspace_repos` / `read_sibling_wiki` / `read_sibling_repo_state` when a workspace manifest is present — faster and more accurate than filesystem grep.

**Knowledge layer** — Project-local wiki under `.claude/wiki/` (auto-generated `REPO_STATE.md`, concept pages, primer). The primer ships with every turn so Claude starts each conversation already oriented. A bundled `wiki-librarian` subagent fields project-design questions in its own fresh context, citing concept pages and verifying claims against current source. A bundled `wiki-author` subagent drafts page edits when the commit-driven drift detector finds stale claims; with **Auto-update wiki on drift** enabled it lands edits unattended in the background. `/seed-workspace` assembles a multi-repo manifest for cross-repo navigation via `read_sibling_wiki` / `read_sibling_repo_state`. Teams can share a single wiki via git: run `/wiki-relocate docs/llm-wiki` to commit the wiki path to `.clawdea/config.json`; teammates auto-discover it on clone. See the [User Guide](docs/user-guide.md#wiki-team-mode) for details.
Check out ClawDEA's own self-maintained wiki at https://github.com/adobe/ClawDEA/blob/main/.claude/wiki/index.md

**Debugger integration** — 21 MCP tools let Claude drive IntelliJ's debugger: launch sessions, set breakpoints (with conditions and log expressions), step through code, inspect variables, evaluate expressions, and modify values at runtime. Breakpoint ownership tracking ensures your breakpoints are never deleted.

**Profiling** — Claude can profile your code via JDK Flight Recorder: launch tests or run configurations with JFR instrumentation, then analyze CPU hotspots, allocation pressure, and memory leaks. Three entry points: `/profile` slash command, gutter icon on `@Test` methods, or imported `.jfr`/`.hprof` files. Claude reads the analysis results and proposes source-level fixes.

**Edit review** — When "Auto-accept Edits" is off, each proposed change opens a native IntelliJ diff dialog with Accept/Reject. Built-in Edit/Write calls that slip through are caught by a fallback layer with inline buttons and file revert.

**Tool permissions** — Three approval modes (Confirm all / Allow safe / Allow all). When the CLI requests permission for a tool call, ClawDEA honors Claude Code `permissions.allow` / `permissions.deny` rules first; otherwise an inline permission card appears in the chat tab that triggered the tool call (multi-panel routing) with Allow / Always allow / Deny buttons. The CLI blocks until you decide.

**@ mentions** — Type `@` for inline autocomplete (open editor tabs + recently git-modified files); press `@` then Tab to open a full picker with grouped Files and Symbols sections, backed by IntelliJ's filename and short-name caches.

**Inline completions** — Tab-completions powered by the Claude API, using editor context gathered by the context engine.

**Intention actions** (Alt+Enter) — Explain, Optimize, Generate Test, Security Check, Add Documentation, Refactor, Ask Claude, Fix with Claude.

**Slash commands** — `/stop`, `/clear`, `/mode`, `/cost`, `/compact`, `/context`, `/resume`, `/skills`, `/login`, `/cc`, `/init`, `/profile`, `/callers`, `/usages`, `/implementations`, `/supertypes`, `/refresh-view`, knowledge-layer commands (`/note`, `/promote-to-wiki`, `/learn`, `/seed-wiki`, `/refresh-wiki`, `/wiki-audit`, `/wiki-gap`, `/wiki-relocate`, `/seed-workspace`), plus Claude Code skills discovered at runtime.

**Session resume** — Pick up a previous Claude Code session with conversation history replayed in the chat panel.

## Requirements

- IntelliJ IDEA 2026.1+
- Java 21
- [Claude Code CLI](https://docs.anthropic.com/en/docs/claude-code) installed (`npm install -g @anthropic-ai/claude-code`)
- Anthropic API key, Claude subscription, or AWS Bedrock / Google Vertex credentials

## Quick Start

1. Install the CLI: `npm install -g @anthropic-ai/claude-code`
2. Install the plugin from a release zip (or build from source — see below).
3. Open **Settings → Tools → ClawDEA** and configure your API key or sign in with a Claude subscription.
4. Press **Cmd+\\** to open the chat panel and start coding.

See the **[User Guide](docs/user-guide.md)** for detailed configuration, slash commands, debugger workflows, and troubleshooting.

## Development

```
./gradlew runIde                           # Launch sandboxed IDE with the plugin
./gradlew test                             # Unit tests
./gradlew build -x buildSearchableOptions  # Full build (skip searchable options if IDE is running)
```

## Project Structure

```
src/main/kotlin/com/adobe/clawdea/
  actions/       Intention actions, context menu, keyboard shortcuts
  chat/          ChatPanel, MessageRenderer, EditDiffReviewer, EditReviewCoordinator
  cli/           CliProcess, CliBridge, CliEventParser, stream-json protocol
  commands/      Slash command registry and handlers
  completions/   Inline completion provider
  context/       Context engine for gathering editor state
  debug/         DebugBridge, McpDebugTools, BreakpointTracker, SuspendGate
  gateway/       Claude API gateway for completions
  knowledge/     Drift detection, wiki maintenance, wiki-author auto-apply
  mcp/           MCP HTTP server, tool router, index/IDE/context/edit-review tools
  profiling/     JFR backend, CPU/allocation/leak analysis, MCP profiling tools
  settings/      Plugin settings and configurable UI
  skills/        Skill scanner and picker dialog

scripts/drift/   Claude Code drift monitoring (watchlist, snapshot collector, issue filer)
.github/workflows/claude-code-drift.yml   Weekly drift digest workflow
src/test/resources/cli-fixtures/   Recorded NDJSON fixture replayed by CliFixtureReplayTest
```

## Staying in sync with Claude Code

ClawDEA wraps the Claude Code CLI as a subprocess. To catch upstream changes (new flags, renamed stream-json fields, MCP config schema additions) without manual scanning, the repo runs a two-part monitoring system:

- A **weekly drift digest** (`.github/workflows/claude-code-drift.yml`) diffs `claude --help`, sub-command help, npm version, and selected docs URLs against a moving git tag (`drift-snapshot`); files an issue when a watchlisted regex matches a changed line.
- A **PR-time fixture replay test** (`CliFixtureReplayTest`) replays a recorded NDJSON transcript through `CliEventParser`; goes red if a new event type appears upstream.

See [`docs/drift-monitoring.md`](docs/drift-monitoring.md) for the full operator guide — adding watchlist entries, triaging auto-filed issues, refreshing the fixture, reseeding the snapshot tag.

## Contributing

Contributions are welcome! Read [CONTRIBUTING](.github/CONTRIBUTING.md) for
details on the contribution process and the [Code of Conduct](CODE_OF_CONDUCT.md)
that all contributors are expected to follow.

All contributors must sign the [Adobe Contributor License Agreement](https://opensource.adobe.com/cla.html)
before their pull requests can be merged.

## Security

See [SECURITY.md](SECURITY.md) for our vulnerability disclosure process.
Please do not report security issues through GitHub issues.

## License

ClawDEA is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE)
for the full text and [NOTICE](NOTICE) for required attributions.

"Claude" and "Claude Code" are trademarks of Anthropic, PBC. "IntelliJ",
"IntelliJ IDEA", and "JetBrains" are trademarks of JetBrains s.r.o. References
to these names in this project are factual identifiers of third-party products
this plugin integrates with, and are not claims of affiliation with or
endorsement by their respective owners.
