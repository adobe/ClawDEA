# Chat UI

The chat panel is a **JCEF browser** rendering streamed Claude responses as HTML, paired with a Swing input area. `ChatPanel` is the EDT-bound coordinator: it observes the `CliBridge.events` flow, hands events to `EventStreamHandler` for HTML rendering through `MessageRenderer`, and routes user input through the slash-command pipeline. Most subsystems (edit review, permission prompts, mention autocomplete, drift banner, task widget, model picker) attach to `ChatPanel` as collaborators rather than living inside it.

## Related

- [Turn state machine](turn-state-machine.md) — Idle / Streaming / Paused state managed per-panel
- [Edit review](edit-review.md) — Layer 2 fallback runs inside the chat panel
- [Permission prompt](permission-prompt.md) — permission cards render inline
- [Mentions and completions](mentions-and-completions.md) — `@` autocomplete attaches to the input area
- [CLI bridge](cli-bridge.md) — chat panel owns no CLI process; it consumes events from a shared bridge

## Key entry points

- [ChatPanel.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatPanel.kt) — top-level panel, EDT lifecycle, event stream observer, slash-command dispatch
- [ChatPanelHost.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatPanelHost.kt) — host/input interfaces collaborators talk through (avoids `ChatPanel` import cycles)
- [ChatToolWindowFactory.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatToolWindowFactory.kt) — registers the tool window
- [ChatBrowserRenderer.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatBrowserRenderer.kt) / [ChatHtmlTemplate.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatHtmlTemplate.kt) — JCEF HTML scaffolding and JS bridge
- [MessageRenderer.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/MessageRenderer.kt) — Markdown → HTML, tool-use cards, ref-link parsing
- [EventStreamHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/EventStreamHandler.kt) — fans `CliEvent`s into rendering, edit-capture, and task-widget controllers
- [SessionManager.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/SessionManager.kt) — chat session lifecycle (new, restart, resume)
- [SlashCommandManager.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/SlashCommandManager.kt) — input parsing and command resolution
- [DriftBanner.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/DriftBanner.kt) — drift event surface above the input
- [TaskWidgetController.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/TaskWidgetController.kt) — Tasks list rendered from `CliEvent.TaskEvent`
- [ChatViewHealthMonitor.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatViewHealthMonitor.kt) — JCEF crash/freeze detection

## Gotchas

- All HTML mutation must go through `MessageRenderer` and `appendHtml` on the EDT — direct JCEF JS injection bypasses the renderer's sanitization and ref-link handling.
- `ChatPanel` does **not** own the `CliBridge`. The bridge is shared per-project, so two chat panels (e.g. tool window + popup) observing the same project see the same event stream.
- `EditReviewCoordinator` capture happens in the chat panel's event handler; if a built-in Edit/Write event is missed there, Layer 2 silent-revert breaks. See [Edit review](edit-review.md).
- `appendHtml` is no-op if the JCEF browser is not yet ready; queued output is replayed on first render via `ChatBrowserRenderer`.
- The thinking/activity indicator is shown once at submit and hidden at turn end, but several mid-turn paths (resume, wake recovery, restart, stall) can drop it — and JCEF can stop painting it during a long, event-sparse sub-agent run. `EventStreamHandler.handleEvent` re-asserts it on each incoming `AssistantMessage`/`ToolResult` by calling `browserRenderer.pokeThinkingIndicator()` (→ `pokeThinking()` JS, which recreates the indicator if absent and nudges a repaint). The gate is `shouldPokeIndicator`: **only** while `isStreaming && !isPaused`, and **only** on those two coarse event kinds — never per-token `TextDelta` (one poke per token would cost a JCEF round-trip each) and never `Result` (it ends the turn and hides the indicator, so poking on it would fight the hide). Because a sub-agent's inner `AssistantMessage`/`ToolResult` events flow through the same handler, this keeps the hint alive for the whole delegated run. See [Subagents](subagents.md).
- **Subagent (Task / Agent) results travel the ordinary `ToolUse` / `ToolResult` path**, not `CliEvent.TaskEvent`. The `TaskEvent` family (`TaskCreated` / `TaskProgress` / etc. in [CliEvent.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliEvent.kt)) is the **todo widget** stream feeding [TaskWidgetController.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/TaskWidgetController.kt) — unrelated to `/agents` subagent dispatch. When Claude invokes `Task`/`Agent`, the delegated run emits ordinary `ToolUse(name="Task"|"Agent")` and one final `ToolResult` with `parentToolUseId` linking back to the launch card; `EventStreamHandler.handleEvent` routes that terminal result through `MessageRenderer.renderToolResult` like any other tool output. See [Subagents](subagents.md).
- **Tool-result output is truncated to 500 characters (with `...` suffix)** in [`MessageRenderer.renderToolResult`](../../../src/main/kotlin/com/adobe/clawdea/chat/MessageRenderer.kt) before HTML rendering. This is a *display-only* cap: the full untruncated text still ships to Claude in the CLI event stream (it's the CLI that decides what the model sees). If a debugging session reports "the model saw a truncated output", the cause is elsewhere — the chat UI never truncates on the CLI-facing side. The cap applies uniformly to subagent results (see above) because they render through the same method.
- **The Swing chrome is responsive to the dock (issue #140).** The leaf controls (tool-approval combo, auto-accept checkbox, Auto/Plan/Ask mode toggle, model/effort combos, cost chip, status + context labels, input area) are built **once** in `buildTitleControls`/`buildBottomControls` and then *reparented* between two arrangements by `applyResponsiveLayout(compact)`. A vertical dock keeps the classic two bands (title bar on top, input + status on the bottom); a **horizontal** dock (bottom/top) merges both control bands into one compact top row and shrinks the input so the limited height goes to the conversation. `recomputeResponsiveLayout()` decides via `isHorizontalDock()` **first** — the tool-window anchor (`ToolWindowManager.getToolWindow("ClawDEA").anchor` == `BOTTOM`/`TOP`) is the authoritative signal for the issue — and falls back to the `ChatPanel.shouldUseCompactLayout` width/height aspect (compact ≥ 1.2, vertical ≤ 1.0, dead-band in between so dragging the splitter near square doesn't flap) when the anchor isn't horizontal or can't be resolved (floating window / tests). It runs from a `ComponentListener` (re-dock / splitter drag) **and** from `addNotify()` (first show, since a resize event can miss a window that opens already docked horizontally). A Swing component can only have one parent, so both layouts **share** the same field instances — never construct a second copy of a control per layout.
