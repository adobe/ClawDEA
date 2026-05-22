# Edit review

**Purpose** Give the user a chance to Accept, Reject, or modify every proposed file edit before it lands on disk, with a fast-path for trusted edits and a fallback for cases where Claude bypasses the MCP tools.

## Invariants

- There are **two layers**, not one. Layer 1 is the preferred path (MCP `propose_edit` / `propose_write` / `propose_multi_edit`); Layer 2 is the safety net for built-in `Edit` / `Write` calls that slip through. Both must be present; removing either breaks the contract that no edit lands without user awareness ([McpEditReviewTools.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpEditReviewTools.kt), [EditReviewCoordinator.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/editreview/EditReviewCoordinator.kt)).
- Layer 1 (`propose_*`) **blocks the MCP HTTP response** until the user clicks Accept or Reject in the diff dialog. The dialog runs on the EDT; the dispatch thread waits on a `CountDownLatch`. The HTTP call returning is the signal to the CLI that the edit is done ([EditDiffReviewer.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/editreview/EditDiffReviewer.kt)).
- Layer 2 captures the **original file content** at the moment a `ToolUse` event for `Edit`/`Write` arrives — **before** the CLI applies the edit. Capturing later means the "original" is already the modified file and revert is silently broken ([EditReviewCoordinator.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/editreview/EditReviewCoordinator.kt)).
- Layer 1 is gated by `autoAcceptEdits = false` in settings AND by the CLI honoring the system-prompt directive to prefer `propose_*`. When auto-accept is on, `propose_*` returns success without prompting and the edit is silently applied ([McpServer.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpServer.kt)).
- Layer 2 reverts a rejected edit by writing the captured `originalContent` back via `WriteAction` and refreshing the VFS through `FilesystemRefreshCoordinator` (never `LocalFileSystem.refresh` directly). Rejected edits also produce a feedback message appended to the next user turn so Claude can correct course ([EditReviewCoordinator.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/editreview/EditReviewCoordinator.kt)).
- The `--disallowedTools` flag in `CliProcess` is what actually convinces the CLI to use `propose_*` instead of `Edit`/`Write`. Without that flag, Claude prefers built-in tools and Layer 2 carries the entire load ([CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)).

## Resolution pipeline

**Layer 1 (preferred)**
1. CLI calls `propose_edit` (or `propose_write` / `propose_multi_edit`) over MCP.
2. `McpEditReviewTools` dispatch thread reads the original file, computes the proposed content (single edit or multi-edit chain), and calls `EditDiffReviewer.review()`.
3. `EditDiffReviewer` schedules a `SimpleDiffRequest` dialog on the EDT via `invokeAndWait`. The dispatch thread blocks on `CountDownLatch`.
4. User clicks Accept / Reject. The dialog's action handler writes the new content (Accept) or does nothing (Reject), counts down the latch, and returns.
5. Dispatch thread wakes, returns `ToolResult` to the CLI describing the outcome.

**Layer 2 (fallback)**
1. CLI emits a `ToolUse` event for `Edit` or `Write` over stream-json.
2. `EventStreamHandler` in the chat panel sees the event, reads the file, calls `EditReviewCoordinator.captureFileContent(toolUseId, path, content, proposedContent)` synchronously **before** the CLI applies the edit.
3. CLI applies the edit. Chat panel renders an inline card with Accept / Reject buttons.
4. On Reject: `EditReviewCoordinator.recordDecision(toolUseId, REJECTED)` revert-writes the captured original via `WriteAction`, then refreshes via `FilesystemRefreshCoordinator`.
5. End of turn: `buildAndClearFeedback()` synthesizes a feedback string from rejected/modified edits and prepends it to the next user message so Claude knows what was undone.

## Anti-patterns

- **Capturing original content after the `ToolUse` is processed** — By then the CLI has already overwritten the file; the "original" is the modified file and revert is a no-op.
- **Calling `LocalFileSystem.getInstance().refresh()` directly** — VFS refresh must go through `FilesystemRefreshCoordinator` for debouncing and write-safe scheduling. Direct refresh from a tool dispatch thread can deadlock or miss write-action wrapping.
- **Returning success from `propose_edit` before the user has decided** — Claude will move on assuming the edit is committed. The HTTP call must block until the dialog closes.
- **Removing the `--disallowedTools` flag** — Claude reverts to built-in `Edit`/`Write` and Layer 1 stops being the primary path. Layer 2 still works but loses the diff-dialog UX.
- **Showing only one of accept/reject when the diff is identical** — `EditDiffReviewer` produces an editable right-hand pane; user-modified diffs are a third outcome (`MODIFIED`) and must produce feedback distinct from `ACCEPTED`.

## Source pointers

- [McpEditReviewTools.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpEditReviewTools.kt) — Layer 1 MCP tools (`propose_edit`, `propose_write`, `propose_multi_edit`, `propose_notebook_edit`)
- [EditDiffReviewer.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/editreview/EditDiffReviewer.kt) — Layer 1 diff dialog with explicit Accept/Reject
- [EditReviewCoordinator.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/editreview/EditReviewCoordinator.kt) — Layer 2 capture, decision tracking, feedback synthesis
- [EditReviewHandler.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/editreview/EditReviewHandler.kt) — Layer 2 inline-card action handlers
- [EditReviewOutcomes.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/editreview/EditReviewOutcomes.kt) — `EditOutcome` enum and feedback formatting
- [FilesystemRefreshCoordinator.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/FilesystemRefreshCoordinator.kt) — debounced VFS refresh used by both layers
