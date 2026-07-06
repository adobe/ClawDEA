# ClawDEA Wiki

ClawDEA is an IntelliJ plugin that wraps the **Claude Code CLI** as a subprocess and exposes IntelliJ's indices, diagnostics, edit-review, debugger, and profiling back to Claude through a local **MCP HTTP server**. The plugin process and the CLI process exchange events via stream-json on stdio while the CLI calls back into the IDE via JSON-RPC over HTTP.

Use these pages to ground reasoning before touching code. Invariants and resolution-pipeline pages are load-bearing — read them before changing the corresponding subsystem.

## Concepts

### Process and protocol
- [CLI bridge](concepts/cli-bridge.md) — subprocess lifecycle, stream-json wiring, system-prompt assembly
- [MCP server](concepts/mcp-server.md) — local HTTP JSON-RPC server, tool registration, dispatch threading
- [Authentication](concepts/authentication.md) — provider fallthrough and effective-provider semantics
- [Tool approval modes](concepts/tool-approval-modes.md) — `confirm-all` / `allow-safe` / `allow-all` policies, CLI argv mapping

### Chat UI
- [Chat UI](concepts/chat-ui.md) — JCEF panel, message rendering, host interfaces
- [Turn state machine](concepts/turn-state-machine.md) — Idle / Streaming / Paused transitions and ESC grace
- [Edit review](concepts/edit-review.md) — two-layer review (MCP propose_* and built-in fallback)
- [Permission prompt](concepts/permission-prompt.md) — 60-second CC cap, abandoned-decision caching
- [Mentions and completions](concepts/mentions-and-completions.md) — `@` autocomplete and inline completion gateway
- [Gateway completions](concepts/gateway-completions.md) — direct API vs CLI fallback for latency-sensitive features
- [Cost tracking and session model](concepts/cost-tracking.md) — per-turn spend accounting, resume seeding, captured model is display-only (never feeds `--model`)
- [Savings estimation](concepts/savings-estimation.md) — ClawDEA-vs-standard-CC savings/cost delta, four levers, signed confidence bands
- [Usage metrics](concepts/usage-metrics.md) — what turn/tool data is captured today, available event hooks, and gaps (no analytics subsystem exists)
- [Subagents](concepts/subagents.md) — `Agent` tool dispatch, `parentToolUseId` card routing, static `--agents` definitions, per-agent tool allowlisting

### Knowledge layer
- [Knowledge layer](concepts/knowledge-layer.md) — primer assembly order, REPO_STATE, wiki, notes, workspace siblings
- [Drift detection](concepts/drift-detection.md) — wiki/workspace freshness, deterministic auto-apply vs subagent
- [Commands and skills](concepts/commands-and-skills.md) — slash command registry and skill discovery
- [Drift monitoring](concepts/drift-monitoring.md) — upstream Claude Code CLI drift digest and fixture replay

### IDE integrations
- [Debug integration](concepts/debug-integration.md) — Claude-owned breakpoints, SuspendGate semantics
- [Profiling](concepts/profiling.md) — JFR capture, IntelliJ backend selection, analysis tools
- [Language support](concepts/language-support.md) — `LanguageSupport` registry, optional Scala plugin bridge, extension/Language fallback
- [Build tools](concepts/build-tools.md) — Gradle/Maven/sbt/Mill adapters, `detectPrimary` order, tier-4 compile dispatch
