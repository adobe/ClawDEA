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
