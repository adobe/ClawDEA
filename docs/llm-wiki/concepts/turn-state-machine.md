# Turn state machine

**Purpose** Manage the chat panel's UI-only turn state (Idle / Streaming / Paused) so that ESC pauses (SIGINT to the CLI) and a second ESC within a grace window aborts the turn cleanly.

## Invariants

- The state machine is **UI-only**. The CLI is unaware of `Paused` — when the user pauses, ClawDEA SIGINTs the process; when they resume, ClawDEA sends `continue` (or new user text) on stdin. The CLI never sees a "pause" event ([TurnStateMachine.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/TurnStateMachine.kt)).
- A second `Escape` within `PAUSE_GRACE_MS = 600 ms` of entering `Paused` is a no-op; only after the grace window does it transition to `Idle` with `FullyAbort`. This prevents a double-tap from immediately aborting an interactive turn ([TurnStateMachine.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/TurnStateMachine.kt)).
- `EnterInPaused(isBlank=true)` resumes with `ResumeWithContinue` (sends literal `"continue"` to the CLI). `EnterInPaused(isBlank=false)` resumes with `ResumeWithInput` (sends the user's freshly typed text). The placeholder text counts as blank ([TurnStateMachine.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/TurnStateMachine.kt)).
- `StreamResult` arriving while `Paused` resolves to `ClearPausedUi`, **not** `FullyAbort`. This handles the race where the CLI emitted its final Result event between the user pressing ESC and the SIGINT taking effect — the turn already finished, so no error needs rendering ([TurnStateMachine.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/TurnStateMachine.kt)).
- `SessionReset` is a hard reset from any state to `Idle` with `ClearPausedUi`. New chat / restart / `bridge.stop()` go through this path so the panel cannot be left in a dangling `Paused` UI.

## Resolution pipeline

```
Idle ──UserSend──▶ Streaming
Idle ──SessionReset──▶ Idle (None)

Streaming ──Escape──▶ Paused (Pause: SIGINT, banner, focus input)
Streaming ──StreamResult──▶ Idle (None)
Streaming ──SessionReset──▶ Idle (None)

Paused ──Escape (within grace)──▶ Paused (None)
Paused ──Escape (after grace)──▶ Idle (FullyAbort: render "Response aborted by user")
Paused ──EnterInPaused(blank)──▶ Streaming (ResumeWithContinue: send "continue")
Paused ──EnterInPaused(text)──▶ Streaming (ResumeWithInput: send user text)
Paused ──StreamResult──▶ Idle (ClearPausedUi)
Paused ──SessionReset──▶ Idle (ClearPausedUi)
```

The `Pause` action is the only side effect that touches the CLI process — it sends SIGINT via `process.toHandle().destroy()`. All other actions are pure UI updates rendered by the chat panel.

## Anti-patterns

- **Treating `Paused` as cancellation** — Pausing only stops the current generation; the session and conversation state survive. Code that disposes panels or clears history on `Pause` is wrong; that's `SessionReset`'s job.
- **Skipping the grace window** — A first ESC followed by a frustrated second ESC is the most common pause pattern. Removing `PAUSE_GRACE_MS` re-introduces the "I clicked twice and lost the conversation" bug.
- **Sending user text on resume without waiting for the next user keystroke** — `ResumeWithInput` fires only when the user explicitly Enters; auto-resume on focus or model change loses the user's intent.
- **Forgetting `ClearPausedUi` on the late-Result race** — If the renderer treats a Result-after-Escape as a normal completion it will leave the pause banner up; the explicit `ClearPausedUi` action exists to dismiss it.

## Source pointers

- [TurnStateMachine.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/TurnStateMachine.kt) — pure-data transition table; no Swing or CLI dependencies
- [TurnController.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/TurnController.kt) — wires the state machine to the chat panel and `CliBridge`
- [TurnControlsState.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/TurnControlsState.kt) — derived view state for the input toolbar (send/pause/abort buttons)
- [PendingPromptController.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/PendingPromptController.kt) — buffers user input during transitions so a fast typist never loses keystrokes
