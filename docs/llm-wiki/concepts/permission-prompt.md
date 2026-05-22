# Permission prompt

**Purpose** Gate every MCP tool call behind a user-visible approval card (Allow / Always allow / Deny) when the tool approval mode requires it, while staying inside Claude Code's hard 60-second HTTP MCP timeout.

## Invariants

- A single `submit()` call blocks for at most `DEFAULT_PROMPT_TIMEOUT_MS = 45 s`. This must stay safely under Claude Code's hard ~60 s HTTP MCP cap (issue [anthropics/claude-code#50289](https://github.com/anthropics/claude-code/issues/50289), open as of 2026-05) — since CC v2.1.113 the binary ignores the per-server `timeout` field and hard-stops every tool call at ~60 s ([PermissionDispatcher.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/PermissionDispatcher.kt)).
- A timed-out `submit()` returns `Decision.DENY` with `timedOut = true` so the CLI sees a deterministic deny rather than a synthesized HTTP failure. The user's actual decision is **not lost**: it is cached against the `(toolName, inputJson)` key for `PENDING_DECISION_TTL_MS = 5 minutes` so the CLI's next retry of the same call consumes it instantly with no re-prompt ([PermissionDispatcher.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/PermissionDispatcher.kt)).
- `submit()` runs on the MCP **dispatch executor thread**, never the EDT. The render callback is invoked synchronously from `submit()` (the UI then schedules its own EDT work). A render-callback exception causes a safe `DENY`, never a CLI stall ([PermissionDispatcher.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/PermissionDispatcher.kt)).
- The cache key is `toolName + "\u0000" + inputJson`. Two calls with even one byte different in input produce distinct cache entries — the cache cannot accidentally consume a decision intended for a different invocation ([PermissionDispatcher.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/PermissionDispatcher.kt)).
- Routing from MCP entry point to the correct dispatcher is handled by `PermissionRouterRegistry`. Each ChatPanel registers its own `PermissionRouter` and `PermissionDispatcher` on startup; the registry selects the dispatcher by matching `(toolName, inputJson, toolUseId)` against live ToolUse events in each panel's stream, with a brief wait window if the claim-map hasn't yet been populated. Requests that don't match any panel are denied without prompting to prevent spurious timeouts ([PermissionRouterRegistry.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/PermissionRouterRegistry.kt), [McpPermissionPromptTool.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpPermissionPromptTool.kt)).
- "Allow all" mode bypasses the prompt entirely. The MCP handler calls `AutoAllowSignal.notify(toolName, inputJson, toolUseId)` to record the auto-allow; each panel's `EventStreamHandler` later calls `AutoAllowSignal.consume()` when its matching ToolUse arrives, triggering an inline "⚡ Auto-allowed" marker in the tool result ([AutoAllowSignal.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/AutoAllowSignal.kt), [MessageRenderer.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/MessageRenderer.kt)).
- Claude Code `permissions.allow` / `permissions.deny` rules from `.claude/settings.json` are checked **before** the dispatcher is consulted. A rule-allowed tool call goes straight through; a rule-denied call returns deny without rendering ([McpPermissionPromptTool.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpPermissionPromptTool.kt), [ClaudePermissionSettings.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/ClaudePermissionSettings.kt)).

## Resolution pipeline

1. CLI tool-call arrives at MCP server. `McpPermissionPromptTool` checks the tool approval mode and Claude Code permission rules.
2. If mode is "allow-all": `notify(toolName, inputJson, toolUseId)` records the tool in `AutoAllowSignal`, then returns allow. No interactive prompt.
3. If mode requires confirmation: `PermissionRouterRegistry.route(toolName, inputJson, toolUseId, waitMs=2000ms)` finds the dispatcher for the matching panel:
   - Poll each registered router's `claimById(toolUseId)` (preferred), or fallback to `claim(toolName, inputJson)` if id is unavailable.
   - Wait up to 2 s for a claim; if the panel's ToolUse hasn't yet populated its claim-map, this delay covers the race.
   - If no panel claims the call: return `DENY` without prompting (prevents spurious stalls when called outside an active panel).
4. Dispatcher `submit(toolName, inputJson)` first calls `consumePendingDecision(cacheKey)` — if a previous prompt timed out and the user has since decided, return that cached decision immediately.
5. Otherwise, build a `PermissionRequest` with a fresh `requestId`, register it in `inFlight`, invoke `onRender(request)` so the chat panel emits the card.
6. Dispatch thread waits on `request.latch.await(45_000ms)`.
7. **Outcome A — user clicks within 45 s**: `resolve(requestId, decision)` fires the latch. Submit returns `Result(decision, updatedInput)` and the CLI gets the answer.
8. **Outcome B — timeout**: `submit` records the request id in `abandoned[requestId] = cacheKey`, returns `Result(DENY, timedOut=true)`. The card is **still visible**; the user can still decide.
9. **Outcome B continued — user decides after timeout**: `resolve` finds the abandoned id, parks the decision in `pendingDecisions[cacheKey]` (TTL 5 min). The next CLI retry of the same call hits step 4 and consumes it without re-prompting.
10. `pruneExpiredDecisions` is called inside `consumePendingDecision` and `resolve` to bound memory.
11. When a ToolUse is rendered, `EventStreamHandler` calls `AutoAllowSignal.consume(toolUseId)` or `.consume(toolName, inputJson)` to check for a pending auto-allow signal, then renders an inline marker if found.

## Anti-patterns

- **Raising `DEFAULT_PROMPT_TIMEOUT_MS` to or above 60 s** — Claude Code will hard-stop the call before the latch can fire. The deny will be synthesized by the CLI rather than ClawDEA, the cached-decision recovery path will never trigger, and the model will "try something else" instead of waiting for the user.
- **Caching by `toolName` only** — Two distinct calls (e.g. `Bash("ls")` and `Bash("rm -rf /")`) would share a decision. The cache key must include `inputJson`.
- **Resolving a request from the EDT without acquiring `lock`** — Race between `submit`'s timeout cleanup and `resolve`'s pending-decision write produces lost or duplicated decisions. All `inFlight` / `abandoned` / `pendingDecisions` mutations go through `synchronized(lock)`.
- **Showing the card after `submit` returns** — The render callback runs synchronously inside `submit` so the UI is up before the latch wait begins. Deferring render to a coroutine post-submit reintroduces a window where the user has no idea why the conversation has stalled.
- **Bypassing the routing registry** — Calling `dispatcher.submit()` directly without going through `PermissionRouterRegistry.route()` means multi-panel scenarios will route the card to the wrong (most-recently-focused) panel. Always route first to find the dispatcher that owns the tool call.
- **Failing to call `AutoAllowSignal.consume()` in EventStreamHandler** — When a ToolUse is rendered and an auto-allow signal was pending, the marker must be consumed and shown inline. Missing this call means the auto-allow indication never surfaces to the user.
- **Not TTL-pruning `AutoAllowSignal` entries** — Entries are bounded and pruned on each `notify()` and `consume()` call. A dropped or abandoned signal (e.g., a tool call that never materialized) stays in the queue until its TTL expires; missing prune calls allow memory to grow.

## Source pointers

- [PermissionDispatcher.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/PermissionDispatcher.kt) — submit/resolve, latch, abandoned-decision cache
- [PermissionRouterRegistry.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/PermissionRouterRegistry.kt) — per-call routing to the correct dispatcher by (toolName, inputJson, toolUseId)
- [PermissionRouter](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/PermissionRouterRegistry.kt) (interface in PermissionRouterRegistry.kt) — interface each panel implements to claim tool calls by id or (toolName, inputJson)
- [AutoAllowSignal.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/AutoAllowSignal.kt) — handoff between MCP auto-allow handler and per-panel ToolUse rendering for inline markers
- [PermissionRequest.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/PermissionRequest.kt) — request record (id, tool, input, decision, latch, updatedInput)
- [PermissionRequestHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/PermissionRequestHandler.kt) — UI-side resolve dispatch from card actions
- [PermissionRequestRenderer.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/PermissionRequestRenderer.kt) — HTML for permission cards
- [PermissionSummaryBuilder.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/PermissionSummaryBuilder.kt) — human-readable summary of `inputJson` per tool
- [ClaudePermissionSettings.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/ClaudePermissionSettings.kt) — `.claude/settings.json` permissions reader
- [McpPermissionPromptTool.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpPermissionPromptTool.kt) — MCP entry point that checks approval mode, routes via registry, and calls `submit` or `notify`
- [MessageRenderer.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/MessageRenderer.kt) — renders inline "⚡ Auto-allowed" marker when tool result is emitted
- [AskUserQuestionInput.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/permission/AskUserQuestionInput.kt) — schema for the `AskUserQuestion` tool that folds answers back via `updatedInput`
