# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
./gradlew compileKotlin                    # Fast compile check
./gradlew test                             # Unit tests (headless-safe subset)
./gradlew build -x buildSearchableOptions  # Full build; skip searchable options if IDE is running
./gradlew runIde                           # Launch sandboxed IDE with plugin loaded
```

`buildSearchableOptions` fails when IntelliJ is already running ("Only one instance of IDEA can be run at a time") — always pass `-x buildSearchableOptions` in that case.

The plugin zip lands in `build/distributions/ClawDEA-<version>.zip`.

Fixture-based tests (`IndexQueryHandlerTest`) are excluded from `./gradlew test` because they hang headlessly — run those from the IDE test runner only.

Version is in `gradle.properties` (`pluginVersion`). Bump it there for releases.

## Architecture

ClawDEA is an IntelliJ plugin that wraps the **Claude Code CLI** (`claude`) as a subprocess and exposes IntelliJ's indices back to Claude via a local **MCP HTTP server**.

### Data flow

```
User ←→ ChatPanel (JCEF) ←→ CliBridge ←→ CliProcess (claude --output-format stream-json)
                                              ↕ MCP HTTP (127.0.0.1:random)
                                          McpServer → McpToolRouter → {Index, IDE, Context, EditReview} tools
```

1. **ChatPanel** renders a JCEF browser, handles user input, slash commands, and streams CLI events into HTML.
2. **CliBridge** owns the `CliProcess` lifecycle and exposes a `SharedFlow<CliEvent>`.
3. **CliProcess** spawns `claude` in `--output-format stream-json` mode with `--print-turn-events`. It writes a temp MCP config file pointing the CLI at the local McpServer.
4. **CliEventParser** parses NDJSON lines into a sealed `CliEvent` hierarchy (SystemInit, TextDelta, AssistantMessage, ToolUse, ToolResult, Result, AuthFailure, TaskEvent).
5. **McpServer** is a project-level service (HttpServer on `127.0.0.1:0`) that routes JSON-RPC requests through **McpToolRouter** to the tool groups: `McpIndexTools` (find files/symbols/usages/callers), `McpSearchTextTool` (literal/regex content search), `McpIdeTools` (diagnostics, resolve symbol), `McpContextTool` (project context), `McpPrimerTool` (project primer), `McpWikiTools` (`docs/llm-wiki/` search + read), `McpWorkspaceTools` (sibling-repo navigation), `McpEditReviewTools` (propose_edit/propose_write/propose_multi_edit), `McpDebugTools` (21 debugger tools), `McpPermissionPromptTool` (tool-call approval gate).

### Knowledge layer

`PrimerService` assembles the primer shipped with every turn: `CLAUDE.md`, the auto-generated `.claude/REPO_STATE.md`, and the `docs/llm-wiki/index.md` table of contents. Concept pages under `docs/llm-wiki/concepts/` are pulled on demand via `read_wiki_page`/`search_wiki`. `WorkspaceManifest` (loaded from `.clawdea-workspace.md`) powers cross-repo siblings navigation. The personal notes layer writes `.claude/notes/CURRENT.md` via the `/note` command and promotes entries to the wiki via `/promote-to-wiki`. `DriftDetector` opportunistically flags stale wiki source-file links and missing workspace-manifest repo paths.

`WikiLocator` is the single source of truth for the wiki path. In default mode (no `.clawdea/config.json`) it returns `<projectBase>/docs/llm-wiki/` (or whatever `ClawDEASettings.claudeDirName`/`wikiSubdir` resolve to). In team mode, when `<projectBase>/.clawdea/config.json` is present with a non-blank `wikiPath`, it returns `<projectBase>/<wikiPath>`. All wiki-aware code (`McpWikiTools`, `DriftDetectionService`, `DriftStateStore`, `WikiSuggestionWriter`, `ChatPanel`, primer sources) routes through `WikiLocator.getInstance(project).wikiDir()`.

### Wiki team mode

When `.clawdea/config.json` exists at the project root, ClawDEA enters "team mode": the wiki path is read from the file (committed to git so teammates auto-discover it on clone), and `DriftStateStore` splits its persistence into two files:

- `<wikiDir>/.wiki-state.json` — team-shared (`lastSyncedCommit`, `suggestions`), git-tracked.
- `<projectBase>/.clawdea/wiki-state.local.json` — per-user (`lastScanAt`, `dismissed`, `probeMisses`, `userCorrections`), gitignored automatically by `/wiki-relocate`.

`lastSyncedCommit` is the git SHA the wiki currently describes. `CommitWikiDriftDetector` uses `lastSyncedCommit..HEAD` as the range (replacing the legacy `--since lastScanAt` filter), and `DriftDetectionService.bumpSyncedCommit` advances it to HEAD after every successful drift cycle. An empty or unreachable SHA (rebased away) falls back to a one-time first-run baseline. Branch switching is automatic because the team file is git-tracked.

Default-mode users (no `.clawdea/config.json`) keep the single legacy `.drift-state.json` file with no behavior change. The first read in team mode performs an idempotent migration from the legacy file into the split layout.

Opt-in via the `/wiki-relocate <repo-relative-path>` slash command (handled by `WikiRelocateHandler`); cloning a repo where someone already opted in is auto-detection — no manual setup.

### Edit review (two layers)

- **Layer 1 (MCP tools):** When "Auto-accept Edits" is off, the CLI system prompt tells Claude to use `propose_edit`/`propose_write` MCP tools. These open a native IntelliJ diff dialog (`EditDiffReviewer`) and block the HTTP response until Accept/Reject.
- **Layer 2 (fallback):** If Claude uses built-in Edit/Write tools anyway, `EditReviewCoordinator` in ChatPanel captures the original content and renders inline Accept/Reject buttons. Reject reverts the file.

### Turn state machine

`TurnStateMachine` manages Idle → Streaming → Paused transitions. Escape pauses (SIGINT), second Escape aborts. Resume sends "continue" or user text to the CLI.

### Authentication

`SubscriptionAuth` handles Claude subscription sign-in by running `claude auth login --claudeai` as a streaming subprocess. It watches for the paste-code prompt but lets the CLI open the browser itself. Auth state is cached and probed via `SubscriptionAuthProbe` (runs `claude auth status`).

Both `CliProcess` and `SubscriptionAuth` resolve the `claude` binary through `resolveClaudeCliPath()` in `CliProcess.kt` — this handles IntelliJ launched from Finder/Dock where shell PATH isn't inherited.

### Gateway (completions)

`ClaudeGateway` powers inline completions and quick actions via two paths: direct Anthropic API (lower latency, needs API key) or CLI fallback (`claude -p`, works with any auth). `ModelCatalogProbe` discovers available models by probing Anthropic, Bedrock, and subscription endpoints in parallel.

### Slash commands & skills

`CommandRegistry` maps slash commands to handlers: `LocalHandler` (in-process — also covers `/wiki-relocate` via `WikiRelocateHandler`), `BridgeForwardHandler` (forwarded to CLI), `BridgeExpandingHandler` (expands an in-plugin prompt template and forwards to the CLI — used by `/learn`, `/seed-wiki`, `/seed-workspace`, `/refresh-wiki`), `SkillHandler` (scanned from `~/.claude/` skill directories), and knowledge-layer handlers (`NoteAppendHandler`, `PromoteToWikiHandler`, `WikiAuditCommandHandler`). `SkillScanner` discovers skills from plugin cache and user directories, deduplicating by qualified name.

## Key conventions

- **Kotlin only** — no Java sources. Target JVM 21.
- **IntelliJ Platform 2026.1** with `org.jetbrains.intellij.platform` Gradle plugin.
- Dependencies: `com.intellij.java`, `Git4Idea`, `org.jetbrains.plugins.terminal`, plus `gson`.
- **JSON parsing in CliEventParser is manual** (string extraction helpers, no Gson) for performance on high-volume NDJSON streams. Gson is used elsewhere (MCP protocol, gateway).
- Plugin services: `ClawDEASettings` (application-level), `McpServer`/`ContextEngine`/`FilesystemRefreshCoordinator`/`ChatAutoResumeState`/`PrimerService`/`WorkspaceManifest` (project-level), `ClaudeGateway` (application-level).
- All VFS refreshes go through `FilesystemRefreshCoordinator` which debounces and wraps in write-safe context.
- Tests follow the pattern `<ClassName>Test.kt` in the matching test package. Most are pure-Kotlin unit tests; fixture tests extending `LightJavaCodeInsightFixtureTestCase` only run in the IDE.
