# MCP server

**Purpose** Run a local HTTP JSON-RPC server bound to `127.0.0.1:<random>` that exposes IntelliJ's indices, diagnostics, edit review, debugger, profiling, and workspace navigation as MCP tools the CLI can call.

## Invariants

- The server binds to `127.0.0.1` on a **random port** (port 0). No remote access — the CLI subprocess is the only client and discovers the port via `--mcp-config <temp file>` ([McpServer.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpServer.kt)).
- All tool dispatch runs on a dedicated cached executor (`ClawDEA-MCP-dispatch` daemon threads). Tool handlers must not block the EDT and must not block the HTTP server thread ([McpServer.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpServer.kt)).
- Every tool handler returns within Claude Code's hard ~60 s HTTP MCP timeout. Long-running interactive tools (permission prompts) cap at **45 s** to stay safely under the cliff — see [Permission prompt](permission-prompt.md) ([PermissionDispatcher.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/PermissionDispatcher.kt)).
- Tools are registered once in `McpServer.registerTools()` at construction. The router is a flat name → handler map; there is no namespacing or wildcards ([McpToolRouter.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpToolRouter.kt)).
- Tool handlers wrap their body in a try/catch inside `McpToolRouter.dispatch`; any thrown exception becomes a `ToolResult(isError = true)` returned to the CLI rather than a 500 — this keeps the CLI from synthesizing a tool-failure on its own.
- The MCP server is a **project-level service** (`@Service(Service.Level.PROJECT)`), so each open project has its own server, port, and tool registry. The CLI subprocess is also project-scoped; cross-project tool calls are not possible ([McpServer.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpServer.kt)).

## Resolution pipeline

1. **Project open** — `McpServerStartupActivity` triggers `McpServer.getInstance(project)`. The service constructor calls `registerTools()` and `start()`.
2. **registerTools** — instantiates each tool group and calls `registerAll(router)`. Groups: index, search-text, IDE (diagnostics + resolve), context, primer, wiki, workspace, edit-review, debug, profiling, permission-prompt.
3. **start** — creates an `HttpServer` on `InetSocketAddress("127.0.0.1", 0)`, captures the assigned port, mounts the JSON-RPC handler, starts the server.
4. **CLI startup** — `CliProcess` writes `mcpClientConfigJson(port)` to a temp file and passes `--mcp-config <path>`. The CLI now knows where to call back.
5. **Tool call (per request)** — CLI POSTs JSON-RPC, the HTTP handler hands the request to the dispatch executor. Executor calls `router.dispatch(toolName, args)`. Handler runs, returns `ToolResult`, response goes back to the CLI.
6. **Project close** — `Disposable.dispose()` stops the HTTP server, shuts down the dispatch executor.

## get_diagnostics tier fallback

`McpIdeTools.get_diagnostics` is a four-tier strategy that prefers IntelliJ-native analysis and only shells out to a build tool as a last resort. Tiers run in order; each tier only fires when the previous one cannot answer ([McpIdeTools.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpIdeTools.kt)).

1. **DaemonCodeAnalyzer cached highlights** — fast path when the file is open in an editor. Uses `DaemonCodeAnalyzerEx.processHighlights` against the existing document.
2. **InspectionEngine `LocalInspectionTool.checkFile`** — when the file is closed, runs every enabled local inspection. A clean file returns the `"No diagnostics found."` sentinel; **null is reserved exclusively for the caught-exception path** so a clean file does not fall through to the slow tiers.
3. **`CompilerManager.compile`** ([CompilerManagerDiagnostics.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/diagnostics/CompilerManagerDiagnostics.kt)) — IntelliJ's native compile API, async-to-sync wrapped via a `CountDownLatch` scheduled from the EDT. Must not be called from the EDT or it deadlocks. Returns `NotApplicable` / `Aborted` / `Failed` to signal "try the next tier"; `AlreadyInProgress` and `Timeout` short-circuit with an error so the user can retry.
4. **Build-tool subprocess** — external `gradle` / `mvn` / `sbt` / `mill` invocation via `BuildToolRegistry.detectPrimary` and `BuildTool.compileCommandFor`.

Tiers 3+4 share a `TIER_3_4_BUDGET_MILLIS = 55_000` envelope (5 s reserved under the 60 s MCP timeout), split evenly with a `MIN_TIER_4_BUDGET_MILLIS = 5_000` floor so the build tool always gets a real chance to run.

## Language dispatch

Tier 4 (and other language-aware tools) routes through [LanguageSupportRegistry](../../../src/main/kotlin/com/adobe/clawdea/language/LanguageSupportRegistry.kt), which keeps lock-free snapshots indexed by IntelliJ Language id and by file extension. Dispatch order:

1. `LanguageSupportRegistry.forFileExtension(ext)` — used by `McpIdeTools.resolveCompileCommand` to pick a `LanguageSupport` from a file path before any PSI is loaded.
2. `LanguageSupportRegistry.forPsiFile(psiFile)` — tries `Language.id` first, then falls back to extension. The fallback is load-bearing for Scala 3, whose IntelliJ `Language.id` is `"Scala 3"` while ClawDEA registers a single [ScalaLanguageSupport](../../../src/main/kotlin/com/adobe/clawdea/language/ScalaLanguageSupport.kt) keyed off `Language.id == "Scala"` plus extensions `scala` and `sc`.
3. Language-specific PSI work (e.g. Scala's `findRelatedTypes`) is delegated to a soft-loaded service like [ScalaPsiBridge](../../../src/main/kotlin/com/adobe/clawdea/language/scala/ScalaPsiBridge.kt). The bridge is registered only when the IntelliJ Scala plugin is present; `ScalaLanguageSupport` looks it up via `getService` inside a try/catch and returns null when absent, so ClawDEA degrades gracefully on installs without the Scala plugin.

Registration happens once at plugin initialization in [LanguageSupportInitializer](../../../src/main/kotlin/com/adobe/clawdea/language/LanguageSupportInitializer.kt). Registry reads must be lazy for any caller that may run before initialization (e.g. `CandidateFingerprinter` during indexing).

## Anti-patterns

- **Blocking the HTTP server thread** — All tool work must happen on the dispatch executor. Blocking the server thread serializes all tool calls and is observable as user-facing latency on simple lookups.
- **Running write-action tools on the dispatch executor without scheduling on EDT** — Edit-review and breakpoint mutation must be scheduled via `ApplicationManager.invokeAndWait` / `WriteAction`. Doing the write on the dispatch thread directly throws `Already on read action` or `Write access not allowed`.
- **Holding a tool response longer than ~45 s** — Claude Code v2.1.113+ ignores the per-server `timeout` field (issue #50289) and will hard-stop the call at ~60 s. Long interactive flows must time out earlier and use a deferred-decision cache; see [Permission prompt](permission-prompt.md).
- **Adding tools without re-checking `--disallowedTools` in `CliProcess`** — If a new MCP tool shadows a built-in (e.g. `propose_edit` shadows `Edit`), the CLI must be told not to use the built-in via `--disallowedTools`.

## Source pointers

- [McpServer.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpServer.kt) — HTTP server lifecycle, tool registration, dispatch executor
- [McpToolRouter.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpToolRouter.kt) — name → handler map, dispatch with error capture, tools/list JSON
- [McpProtocol.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpProtocol.kt) — JSON-RPC envelope, tool schema serialization
- [McpClientConfig.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpClientConfig.kt) — generates the config JSON the CLI consumes via `--mcp-config`
- [McpServerStartupActivity.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpServerStartupActivity.kt) — startup hook
- [McpIndexTools.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpIndexTools.kt), [McpSearchTextTool.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpSearchTextTool.kt), [McpIdeTools.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpIdeTools.kt), [McpContextTool.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpContextTool.kt), [McpPrimerTool.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpPrimerTool.kt), [McpWikiTools.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpWikiTools.kt), [McpWorkspaceTools.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpWorkspaceTools.kt), [McpEditReviewTools.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpEditReviewTools.kt), [McpPermissionPromptTool.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpPermissionPromptTool.kt) — tool groups
