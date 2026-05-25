# Tool approval modes

**Purpose** Three user-selectable policies (`confirm-all` / `allow-safe` / `allow-all`) that control whether a tool call from the CLI is gated by an interactive prompt, auto-approved, or routed through Anthropic's auto-mode classifier. The mode shapes both the CLI invocation flags and the [Permission prompt](permission-prompt.md) decision graph.

## Invariants

- The mode is stored as a string key on `ClawDEASettings.state.toolApprovalMode` (default `"confirm-all"`). Valid keys are `"confirm-all"`, `"allow-safe"`, `"allow-all"`. The UI label/icon mapping lives in [ToolApprovalModeUi.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ToolApprovalModeUi.kt) and is the single source of truth for the human-readable forms ("Ask unlisted", "Allow safe", "Allow all"). Persisting an unknown key is allowed but treated like `confirm-all` ([ClawDEASettings.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettings.kt), [ToolApprovalModeUi.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ToolApprovalModeUi.kt)).
- Changing the mode requires a CLI restart. `ToolApprovalModeUi.requiresCliRestart(oldKey, newKey)` returns true on any change because both `--permission-mode` and the temp `--settings` file are baked into the subprocess argv. `ChatPanel.applyToolApprovalModeChange` triggers `restartAfterToolApprovalModeChange` when the bridge is running and not streaming ([ChatPanel.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatPanel.kt)).
- `McpServer.activeToolApprovalMode` is the **session-effective** mode used by the permission prompt tool. It is initialised from `ClawDEASettings` on first read and updated by `ChatPanel` when the user picks a new mode. `CliProcess` reads `McpServer.activeToolApprovalMode` (not `ClawDEASettings`) when building the argv so a mid-flight change without restart still uses the prior session's argv but the new prompt-tool decision graph ([McpServer.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpServer.kt), [CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)).
- Mode → CLI argv mapping (`buildPermissionArgs` in [CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)):
  - `allow-safe` → `--permission-mode auto` (Anthropic's native auto-mode classifier auto-approves routine actions; soft-deny falls through to the prompt tool).
  - `confirm-all` and `allow-all` → no `--permission-mode` flag. Both rely on the `request_permission` MCP tool (`--permission-prompt-tool`) for gating.
- Mode → temp `--settings` JSON mapping (`buildPermissionSettingsJson` in [CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt)):
  - `confirm-all` → injects a session-only `permissions.ask` rule list for read-only Bash commands (`ls`, `pwd`, `cat`, `head`, `tail`, `grep`, `find`, `wc`, `diff`, `stat`, `du`, `cd`, `git` and their `*`-glob forms). Without this, Claude Code skips the prompt for these commands and ClawDEA can't gate them.
  - `allow-safe` and `allow-all` → no temp settings JSON; Claude Code's defaults plus the prompt tool handle the rest.
- The `request_permission` decision graph ([McpPermissionPromptTool.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpPermissionPromptTool.kt)) is mode-aware in **only one place**: `shouldSilentlyAllow(toolName)` returns true when `toolApprovalMode == "allow-all"` AND the tool is not `AskUserQuestion`. Trusted MCP tools (everything starting `mcp__clawdea-intellij__` plus `Read`/`Glob`/`Grep`) are auto-allowed regardless of mode by `isAutoAllowed`. `.claude/settings.json` `permissions.allow`/`permissions.deny` rules are evaluated **before** the mode is consulted.
- `allow-all` deliberately does **not** pass `--dangerously-skip-permissions`. Enterprise policies often strip that flag and it is risky in general; instead the prompt tool itself silently approves and emits a compact "auto-allowed" notice in the chat transcript via `AutoAllowSignal`. See [Permission prompt](permission-prompt.md) for the inline-marker handoff.
- `AskUserQuestion` is **never** silently allowed even under `allow-all`. Auto-allowing would feed the CLI an empty answer set and the model would receive a useless "answer came through empty" result (issue #141) ([McpPermissionPromptTool.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpPermissionPromptTool.kt)).

## Resolution pipeline

1. **User picks a mode** in the ChatPanel combo box. `ToolApprovalModeUi.keyForIndex(selectedIndex)` maps the UI selection to the storage key.
2. **`ChatPanel` writes** `settings.toolApprovalMode = newKey` and calls `applyToolApprovalModeChange(label)`. If the bridge is running and idle, it calls `restartAfterToolApprovalModeChange` which restarts the CLI subprocess.
3. **CLI argv assembly** (`CliProcess.buildCommand`, gated on `mcpPort > 0`):
   - Reads the **effective** mode: `McpServer.getInstance(project).activeToolApprovalMode` (falls back to settings when no project).
   - Adds `buildPermissionArgs(effectiveMode)` (e.g. `--permission-mode auto` for `allow-safe`).
   - Computes `buildPermissionSettingsJson(effectiveMode)`. Non-null JSON is written to a temp file and passed as `--settings <file>` (the temp-file form sidesteps Windows `cmd.exe` quote stripping that breaks inline JSON).
4. **CLI tool call arrives at `request_permission`** ([McpPermissionPromptTool.handle](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpPermissionPromptTool.kt)):
   1. `isAutoAllowed(toolName)` — trusted MCP / `Read` / `Glob` / `Grep` → allow without UI.
   2. `policyResponse(toolName, inputJson)` — `.claude/settings.json` `allow`/`deny` rules → respect.
   3. `shouldSilentlyAllow(toolName)` — `allow-all` (and not `AskUserQuestion`) → silently allow + `AutoAllowSignal.notify(...)` → inline "Auto-allowed" marker is rendered when the matching `ToolUse` event arrives.
   4. Otherwise → route via `PermissionRouterRegistry` to the owning panel's `PermissionDispatcher.submit(...)` and render an interactive card.
5. **Auto-accept Edits decoupling** — `autoAcceptEdits` is independent of `toolApprovalMode`. `ChatPanel` treats `autoAcceptEdits || ToolApprovalModeUi.isAllowAll(toolApprovalMode)` as the effective auto-accept flag for built-in Edit/Write tools (Layer 2 fallback).

## Anti-patterns

- **Reading `settings.toolApprovalMode` directly from a hot tool-call path** — use `McpServer.activeToolApprovalMode` so a mid-session restart picks up the new value. The only reader of the raw settings field at argv-build time is `CliProcess.buildCommand` (and only as a fallback when project is null).
- **Adding a fourth mode without updating `ToolApprovalModeUi.modes`** — the combo box, label/icon mapping, and `isAllowAll` checks all flow through this one list. Hard-coding a new key in `CliProcess` or `McpPermissionPromptTool` without registering it in `ToolApprovalModeUi` will surface as an unknown UI label.
- **Adding `--dangerously-skip-permissions` for `allow-all`** — explicitly rejected. Enterprise policies often strip it; the prompt-tool silent-approve path is the canonical implementation and emits a user-visible notice.
- **Auto-allowing `AskUserQuestion`** — feeds empty answers back to the model. The `shouldSilentlyAllow` check explicitly excludes `AskUserQuestion` for this reason.
- **Inlining the `--settings` JSON on the CLI command line** — Windows `cmd.exe` strips inner double quotes and splits on unquoted spaces, which destroys the JSON. Always write to a temp file and pass `--settings <path>`.
- **Forgetting to restart the CLI on mode change** — `ToolApprovalModeUi.requiresCliRestart` returns true on any change. The `--permission-mode` flag and `--settings` JSON are baked into argv; without a restart, only the prompt-tool decision graph updates and the CLI's local rules go stale.

## Source pointers

- [ToolApprovalModeUi.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ToolApprovalModeUi.kt) — labels, icons, combo-box renderer, `isAllowAll`, `requiresCliRestart`
- [ClawDEASettings.kt](../../../src/main/kotlin/com/adobe/clawdea/settings/ClawDEASettings.kt) — persisted `toolApprovalMode` field
- [McpServer.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpServer.kt) — `activeToolApprovalMode` session-effective field used by the prompt tool
- [CliProcess.kt](../../../src/main/kotlin/com/adobe/clawdea/cli/CliProcess.kt) — `buildPermissionArgs`, `buildPermissionSettingsJson`, temp-`--settings` file handling
- [McpPermissionPromptTool.kt](../../../src/main/kotlin/com/adobe/clawdea/mcp/McpPermissionPromptTool.kt) — `isAutoAllowed`, `shouldSilentlyAllow`, decision order
- [ChatPanel.kt](../../../src/main/kotlin/com/adobe/clawdea/chat/ChatPanel.kt) — combo-box wiring, `applyToolApprovalModeChange`, `restartAfterToolApprovalModeChange`
- [Permission prompt](permission-prompt.md) — interactive-card pipeline this mode policy hands off to
