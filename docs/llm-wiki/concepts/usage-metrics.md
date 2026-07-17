# Usage metrics

**Purpose** Document what usage data ClawDEA currently captures, where each data point originates, and what event hooks are available — but not yet instrumented — for anyone designing a broader metrics layer.

## Invariants

- **There is no dedicated analytics or telemetry subsystem.** No `analytics`, `telemetry`, or `metrics` package exists in the source. The only per-turn data collected is cost and token usage, handled entirely by [`CostTracker`](../../../src/main/kotlin/com/adobe/clawdea/cost/CostTracker.kt) as an accounting mechanism, not a general metrics store.
- **All observable turn data flows through `CliEvent.Result`.** Every turn ends with a single `Result` event carrying `costUsd`, `contextTokens`, `contextWindow`, `inputTokens`, `outputTokens`, `cacheReadTokens`, and `cacheCreationTokens` — these are the only post-turn figures the plugin currently consumes ([CliEvent.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliEvent.kt), [EventStreamHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/EventStreamHandler.kt)).
- **The `rate_limit_event` CLI event type is deliberately dropped.** `CliEventParser` returns `CliEvent.Unknown` for it; `CliFixtureReplayTest` lists it in `KNOWN_IGNORED_RAW_TYPES` with a comment: "Runtime telemetry that ClawDEA does not surface today." This is a named deferred work item ([CliFixtureReplayTest.kt](../../../src/test/kotlin/com/adobe/clawdea/cli/CliFixtureReplayTest.kt)).
- **Tool call data is logged to the IDE log, not to any store.** `CliBridge.logToolEvent` writes `tool_use name=… id=… input_len=…` and `tool_result id=… ok/error content_len=…` to `Logger.getInstance(CliBridge)` on every call. This is the only per-tool instrumentation present; nothing accumulates it ([CliBridge.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliBridge.kt)).
- **Knowledge-bucket classification is the closest thing to intent tagging.** `KnowledgeBucketClassifier.classify` maps a prompt prefix (or a slash-command name) to one of four buckets — `WIKI_CREATE`, `WIKI_UPDATE`, `WORKSPACE_CREATE`, `WORKSPACE_UPDATE` — or `null` for an ordinary turn. The bucket is parked in `EventStreamHandler.pendingKnowledgeBucket` at submit time and consumed when the turn's `Result` arrives. Only the knowledge-upkeep buckets are tagged; ordinary coding turns produce `null` and are not further classified ([KnowledgeBucket.kt](../../../src/main/kotlin/com/adobe/clawdea/cost/KnowledgeBucket.kt), [EventStreamHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/EventStreamHandler.kt)).
- **Per-tool and per-subagent attribution does not exist today.** `CliEvent.Result` carries only the full-turn aggregate. The stream does carry per-tool data (`AssistantMessage.toolUses`, `ToolResult`, `parentToolUseId`), which `SubAgentController` already groups for rendering, but no path from these events into any accumulator exists. See the `cost-tracking` page for the explicit invariant on this gap ([cost-tracking.md](cost-tracking.md)).

## Available event hooks

These are the stream-observable events that a metrics layer could subscribe to — none is currently wired to an accumulator beyond what cost tracking already does.

### From `CliBridge.events: SharedFlow<CliEvent>`

This is the single fan-out point. Any coroutine collecting from `bridge.events` sees all events in order. `EventStreamHandler.startEventListener` is the current sole subscriber per chat panel.

| Event | Data available | Currently consumed for |
|---|---|---|
| `CliEvent.SystemInit` | `sessionId`, `model`, `tools` list | Model footer seeding |
| `CliEvent.AssistantMessage` | `toolUses` (id/name/input for each), `model`, `parentToolUseId` | Rendering, sub-agent grouping |
| `CliEvent.ToolResult` | `toolUseId`, `content`, `isError`, elapsed (computed in handler) | Rendering, edit review |
| `CliEvent.Result` | Full token breakdown, `costUsd`, `contextTokens`, `contextWindow`, `sessionId`, `isError` | Cost accounting, context indicator |
| `CliEvent.BackgroundTask` | `toolUseId`, `phase` (STARTED/PROGRESS/NOTIFICATION), `status`, `summary`, `lastToolName`, `toolUses` count | Background sub-agent card lifecycle (no accumulator) |
| `CliEvent.Unknown` (rawType=`rate_limit_event`) | Raw JSON — structure undocumented by CC | Dropped |
| `CliEvent.TaskEvent.*` | Task id/subject/status changes | Task widget |
| `CliEvent.GoalFeedback` | `condition`, `reason` | Goal banner |

### From `TurnStateMachine`

State transitions (Idle ↔ Streaming ↔ Paused) are fired by `TurnController`, which wraps `TurnStateMachine`. Each `TurnAction` result (`Pause`, `FullyAbort`, `ResumeWithContinue`, `ResumeWithInput`) corresponds to a user gesture. These are currently handled inline in `ChatPanel` with no external emission ([TurnStateMachine.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/TurnStateMachine.kt)).

### From `McpToolRouter.dispatch`

`McpServer` dispatches every incoming JSON-RPC tool call through `McpToolRouter.dispatch(toolName, arguments)`. The router has no instrumentation callback today; adding one at this single callsite would cover all MCP tool invocations from the CLI side ([McpToolRouter.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpToolRouter.kt), [McpServer.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpServer.kt)).

## What is persisted today

| Datum | Granularity | Where stored |
|---|---|---|
| Session cost (`sessionUsd`, `perModelUsd`) | Per chat tab (`chatId`) | `CostTracker` (project-level `@Service`, in-memory) |
| Daily cost total | Application-wide | `ClawDEASettings.state.dailyCostUsd` + `dailyCostDate` |
| Knowledge-upkeep cost | Per `KnowledgeBucket`, global | `ClawDEASettings.state.knowledgeUsd` |
| Provider cumulative totals | Per `providerId`, monthly + all-time | `ClawDEASettings.state.providerTotals` |
| Live subscription usage | Application-wide, polled ~5min | `OAuthUsageCache` (in-memory, ephemeral) |

Nothing else — turn count, tool call frequency, latency, error rate, model swap events — is stored or exported anywhere.

## Anti-patterns

- **Adding per-tool metrics inside each `McpXxxTools` handler** — a cross-cutting concern belongs at the `McpToolRouter.dispatch` callsite, not scattered across every tool group.
- **Collecting latency from `EventStreamHandler`** — the handler runs on the EDT and includes rendering time. For pure CLI latency, measure between `CliBridge.sendMessage` and the matching `CliEvent.Result` on the IO thread.
- **Treating `KnowledgeBucket` as a general intent classifier** — it covers exactly four knowledge-upkeep command prefixes and returns `null` for everything else. It is not extensible for arbitrary prompt categorization without modifying the enum and classifier.
- **Storing anything sensitive from tool inputs** — `AssistantMessage.toolUse.input` is arbitrary JSON that may contain file contents, user code, or personal data. Any metrics store must redact or hash inputs before persistence.

## Source pointers

- [CliEvent.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliEvent.kt) — the full sealed event hierarchy; `Result` is the only per-turn summary event
- [CliBridge.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliBridge.kt) — `events: SharedFlow<CliEvent>`, the single fan-out point; `logToolEvent` for the IDE-log-only per-tool trace
- [EventStreamHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/EventStreamHandler.kt) — sole current subscriber of `bridge.events`; `pendingKnowledgeBucket` lifecycle
- [McpToolRouter.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpToolRouter.kt) — `dispatch(toolName, arguments)`, the single MCP tool callsite
- [McpServer.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpServer.kt) — submits dispatches to `dispatchExecutor`; timing a future here would give per-tool wall-clock latency
- [KnowledgeBucket.kt](../../../src/main/kotlin/com/adobe/clawdea/cost/KnowledgeBucket.kt) — `KnowledgeBucket` enum + `KnowledgeBucketClassifier`; the only intent-classification path today
- [TurnStateMachine.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/TurnStateMachine.kt) — state transitions available as hooks for turn-count or abort-rate metrics
- [CliFixtureReplayTest.kt](../../../src/test/kotlin/com/adobe/clawdea/cli/CliFixtureReplayTest.kt) — `KNOWN_IGNORED_RAW_TYPES` documents `rate_limit_event` as a deferred work item
- [CostTracker.kt](../../../src/main/kotlin/com/adobe/clawdea/cost/CostTracker.kt) — the only accumulator present; see [cost-tracking.md](cost-tracking.md) for its full design
