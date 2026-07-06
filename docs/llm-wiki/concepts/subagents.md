# Subagents

**Purpose** Claude Code can dispatch a scoped sub-conversation ("subagent") via its `Agent` tool. ClawDEA both defines named subagents at spawn time (`--agents` JSON: `wiki-librarian`, `wiki-author`) and groups every in-stream subagent dispatch under a single collapsible UI card whose inner tool events are routed by `parentToolUseId`.

## Invariants

- The sub-dispatch tool is named **`Agent`**, not `Task`. `Task*` and `TodoWrite` in this codebase are the todo widget ([TaskWidgetController](../../../src/main/kotlin/com/adobe/clawdea/chat/TaskWidgetController.kt), [MessageRenderer.TASK_TOOLS](../../../src/main/kotlin/com/adobe/clawdea/chat/MessageRenderer.kt) at line 563 = `{"TaskCreate","TaskUpdate","TodoWrite","TodoRead"}`) and share no code with subagents. `SubAgentController.SUBAGENT_TOOLS = setOf("Agent")` ([SubAgentController.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/SubAgentController.kt)) is the sole membership test — do not conflate the two dispatch paths.
- A subagent runs **inside the same CLI turn** as its caller: no second `CliBridge`, no second subprocess, no separate `MutableSharedFlow<CliEvent>`. Every inner tool_use / tool_result flows through the same `EventStreamHandler` and the same MCP permission pipeline as the main agent. The only exception is `WikiAuthorInvoker`, which spawns an out-of-band `claude -p` ([WikiAuthorInvoker.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/WikiAuthorInvoker.kt)).
- A child event belongs to a subagent card iff its `parentToolUseId` equals a currently-active `SubAgentController` entry. `parentCardFor` returns `null` for unknown / deeper-nested ids — depth-1 grouping only ([SubAgentController.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/SubAgentController.kt) `parentCardFor`, line 45). A reasoner cannot assume arbitrary-depth nesting will render as a tree; deeper children fall back to top-level rendering.
- `TextDelta` events whose `parentToolUseId` targets an active subagent are **dropped from the main message buffer** ([EventStreamHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/EventStreamHandler.kt) lines 180-187). The child `AssistantMessage` carries the full text and is appended into the card instead — sub-agent reasoning tokens must not leak into the main bubble.
- Per-agent tool allowlisting is enforced by the CLI via the `tools:` frontmatter of `/agents/<name>.md` files, serialised into `--agents` JSON at spawn time ([WikiAgentsArg.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiAgentsArg.kt) lines 42-69). `wiki-librarian`'s allowlist contains `mcp__clawdea-intellij__record_wiki_suggestion`; `wiki-author`'s does not — this is the design gate that keeps write-back concentrated in the librarian ([McpWikiTools.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpWikiTools.kt) line 196, and the two `WikiAgentsArgTest` assertions).
- One `SubAgentController` per `ChatPanel` ([ChatPanel.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatPanel.kt) line 126) — subagent state does not cross panels. The controller is pure state + query logic, headless-testable; JCEF operations happen in `EventStreamHandler` / `MessageRenderer` / `ChatBrowserRenderer` around it.
- Any card still `RUNNING` when `CliEvent.Result` arrives is force-finalised as `ABORTED` — a turn-end sweep guarantees no card lingers as "running" across turns ([EventStreamHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/EventStreamHandler.kt) lines 426-431).
- Session replay reconstructs cards from persisted history but **cannot recover step counts** — the jsonl carries no inner events, so `HistoryReplayRenderer.renderSubAgentCard` reports `stepCount = 0` and `renderSubAgentSummary` omits the "N steps" phrase in that case ([HistoryReplayRenderer.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/session/HistoryReplayRenderer.kt) lines 98-142, [MessageRenderer.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/MessageRenderer.kt) lines 232-241). Status is inferred: missing agent-result → `ABORTED`; `isError` → `ERROR`; otherwise `DONE`.

## Resolution pipeline

Two layers to distinguish:

**Layer A — static agent definitions (spawn-time).** Only relevant when `settings.enableWikiLibrarian` is on.

1. `CliProcess.start()` reaches the wiki-agents branch ([CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt) lines 123-131) and calls `WikiAgentsArg.buildJson()`.
2. `buildJson` reads `/agents/wiki-librarian.md` and `/agents/wiki-author.md` from the plugin classpath, parses YAML frontmatter (`name`, `description`, `tools`), substitutes `{{wiki-page-invariant}}` / `{{wiki-page-navigation}}` via `PromptResource.load(...)`, and returns a JSON object keyed by agent name.
3. The JSON is passed as `--agents <json>` to the `claude` subprocess. From that point on, the CLI advertises these subagents to the main agent and enforces their `tools:` allowlist when it dispatches them.
4. Separately, `DefaultWikiAuthorInvoker` uses `WikiAgentsArg.buildAuthorOnlyJson()` to spawn a **fresh out-of-band** `claude -p` process for drift-refresh runs ([WikiAuthorInvoker.kt](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/WikiAuthorInvoker.kt) lines 69-98). This is not an in-chat subagent — it is a chatless invocation whose cost is billed to the `WIKI_UPDATE` bucket by `DriftDetectionService`.

**Layer B — live in-stream dispatch (per turn).** Runs on the EDT via `EventStreamHandler`.

1. **Register.** A top-level `AssistantMessage` `ToolUse` with `name == "Agent"` and no `parentToolUseId` reaches `EventStreamHandler.handleEvent` lines 277-283. It extracts `subagent_type` and `description` from the tool input JSON, calls `subAgentController.register(id, agentType, description, now)`, and appends `renderer.renderSubAgentCard(...)` — a `.subagent-block expanded` container with a `.subagent-children` slot ([MessageRenderer.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/MessageRenderer.kt) lines 164-180).
2. **Route children.** Subsequent events carry `parentToolUseId == <card_id>`. `parentCardFor` maps them to the card, `TextDelta`s are dropped from the main buffer (invariant 4), and child `ToolUse`s render as:
   - Edit / propose_edit / propose_write / propose_multi_edit → full `renderToolUseEvent(Live)` — the edit link, so diff/revert still work ([EventStreamHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/EventStreamHandler.kt) lines 209-224).
   - `Read` with a `file_path` → compact file link, matching main-chat behaviour (lines 225-233).
   - Everything else → `renderInnerToolUse` — a one-liner step row whose output is hidden until expanded ([MessageRenderer.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/MessageRenderer.kt) lines 189-218).
   Each rendered step is appended via `browserRenderer.appendIntoSubAgent(parentCard, stepHtml)`, `subAgentController.recordStep(cardId)` increments the counter, and the header updates to `running · N steps`.
3. **Finalise on ToolResult.** When a `ToolResult` arrives whose `toolUseId` matches an active card ([EventStreamHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/EventStreamHandler.kt) lines 316-334), `finalize(id, DONE|ERROR)` removes the entry and `browserRenderer.finalizeSubAgent(id, renderer.renderSubAgentSummary(...))` collapses the card to a one-line summary (glyph + step count + first non-blank line of result text).
4. **Abort sweep on turn end.** On `CliEvent.Result`, `activeIds()` is iterated and every survivor is finalised as `ABORTED` with status "aborted"; the card stays expanded (invariant 7).

Session-resume replay bypasses (1)-(4) and reconstructs each card directly from `HistoryReplayRenderer.renderSubAgentCard` ([HistoryReplayRenderer.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/session/HistoryReplayRenderer.kt) lines 98-142), scanning `HistoryEntry.ToolUse` entries whose `parentToolUseId` matches the outer `Agent` tool_use id and inferring status from whether an agent-result exists.

## Anti-patterns

- **Confusing `Agent` with `Task`.** Adding `"Task"` to `SUBAGENT_TOOLS` (or checking `TASK_TOOLS` when routing subagent children) would double-route TodoWrite events into a subagent card and starve the task widget. Triggered when a reader assumes CLI naming matches the plan-doc vocabulary (`Task` in the 2026-05 wiki-librarian plan meant the tool that dispatched a subagent, which shipped renamed to `Agent`). `SubAgentController.kt`'s companion comment is the anchor.
- **Assuming subagents are a separate process.** A common wrong mental model is that the CLI forks per subagent. It does not — the same `stdout` stream carries interleaved parent and child events, distinguished only by `parentToolUseId`. Code that tries to spawn a "second bridge" for subagents (proposed in an early draft) would deadlock the shared permission pipeline.
- **Rendering deeper nesting as a tree.** `parentCardFor` deliberately returns `null` for `parentToolUseId`s that are not currently active. A change that walks the ancestor chain would break the invariant that only depth-1 children render inside a card, and inner tool_uses of a *hypothetical* sub-subagent would leak into the top level anyway once the parent finalised.
- **Trusting replay step counts.** The persisted `session.jsonl` does not record inner `ToolUse` events under a subagent — do not treat `stepCount == 0` on a replayed card as evidence the subagent did nothing. `renderSubAgentSummary` omits the count in that case; UI code that surfaces "0 steps" is a regression.
- **Forgetting the abort sweep.** Removing the `activeIds()` loop on `CliEvent.Result` would leave a card visually "running" after a `/pause` or a CLI crash. The sweep is the only guarantee that the card lifecycle terminates cleanly per turn.

## Source pointers

- [SubAgentController](../../../src/main/kotlin/com/adobe/clawdea/chat/SubAgentController.kt) — pure state (LinkedHashMap), `register` / `parentCardFor` / `recordStep` / `finalize` / `activeIds`; `SUBAGENT_TOOLS = {"Agent"}` companion.
- [EventStreamHandler](../../../src/main/kotlin/com/adobe/clawdea/chat/EventStreamHandler.kt) — register at 277-283, child routing at 184-238, finalise at 316-334, abort sweep at 426-431.
- [MessageRenderer.renderSubAgentCard / renderInnerToolUse / renderSubAgentSummary](../../../src/main/kotlin/com/adobe/clawdea/chat/MessageRenderer.kt) — HTML shapes; `TASK_TOOLS` at line 563 is the disambiguation anchor.
- [ChatBrowserRenderer](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatBrowserRenderer.kt) — `appendIntoSubAgent`, `updateSubAgentStatus`, `finalizeSubAgent` JCEF hooks.
- [ChatPanel](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatPanel.kt) — owns one `SubAgentController` (line 126), wired into `EventStreamHandler` at line 367.
- [WikiAgentsArg](../../../src/main/kotlin/com/adobe/clawdea/knowledge/wiki/WikiAgentsArg.kt) — `buildJson` / `buildAuthorOnlyJson`; YAML-frontmatter parse; `{{...}}` placeholder substitution via `PromptResource.load`.
- [CliProcess](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt) — injects `--agents` at lines 123-131 when `enableWikiLibrarian` is on.
- [WikiAuthorInvoker](../../../src/main/kotlin/com/adobe/clawdea/knowledge/drift/WikiAuthorInvoker.kt) — the out-of-band `claude -p` path with `buildAuthorOnlyJson`; billed to `WIKI_UPDATE`.
- [McpWikiTools](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpWikiTools.kt) — `record_wiki_suggestion` tool name (line 196); its allowlist entry lives in `/agents/wiki-librarian.md` frontmatter.
- [HistoryReplayRenderer.renderSubAgentCard](../../../src/main/kotlin/com/adobe/clawdea/chat/session/HistoryReplayRenderer.kt) — replay path; status inferred from persisted result, `stepCount = 0` because inner events are not persisted.

## Related

- [CLI bridge](cli-bridge.md) — `--agents` injection is part of the spawn-time flag assembly; the shared stream-json pipeline carries every subagent event.
- [Chat UI](chat-ui.md) — `TaskWidgetController` (TodoWrite todos, disambiguated from subagent cards).
- [Drift detection](drift-detection.md) — the wiki-author out-of-band invocation.
- [Cost tracking](cost-tracking.md) — `WIKI_UPDATE` bucket for wiki-author subprocess spend.
