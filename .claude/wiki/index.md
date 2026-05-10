# ClawDEA Wiki

ClawDEA is an IntelliJ plugin that wraps the Claude Code CLI as a subprocess and exposes IntelliJ's indices back to Claude via a local MCP HTTP server. This wiki documents its major subsystems.

## Concepts

- [CLI Bridge](concepts/cli-bridge.md) — subprocess lifecycle, NDJSON event stream, CliBridge/CliProcess
- [MCP Server](concepts/mcp-server.md) — local HTTP MCP server, tool routing, tool groups
- [Chat UI](concepts/chat-ui.md) — JCEF chat panel, browser renderer, turn state machine
- [Edit Review](concepts/edit-review.md) — two-layer edit review: MCP propose_* tools and fallback coordinator
- [Knowledge Layer](concepts/knowledge-layer.md) — primer assembly, repo state, wiki, workspace, notes
- [Drift Detection](concepts/drift-detection.md) — Dream wiki maintenance, scan scheduling, auto-apply
- [Authentication](concepts/authentication.md) — auth providers, subscription sign-in, CLI path resolution
- [Gateway and Completions](concepts/gateway-completions.md) — inline completions, model catalog, API vs CLI fallback
- [Commands and Skills](concepts/commands-and-skills.md) — slash command registry, handler types, skill scanning
- [Debug Integration](concepts/debug-integration.md) — debugger tools exposed via MCP, session lifecycle
- [Profiling](concepts/profiling.md) — JVM profiling: CPU hotspots, memory leaks, hybrid IntelliJ/JFR capture
