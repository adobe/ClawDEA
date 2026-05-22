# Debug integration

**Purpose** Let Claude drive IntelliJ's debugger as a series of MCP tool calls — set breakpoints, step, inspect variables, evaluate expressions, mutate values — without ever stomping on breakpoints the user set themselves.

## Invariants

- **Claude-owned breakpoints are tagged distinctly from user breakpoints**. `BreakpointTracker.claudeBreakpoints` is the single source of truth for which breakpoints `debug_remove_breakpoint` may delete; user breakpoints can only be temporarily disabled via `debug_disable_breakpoint`, never removed ([BreakpointTracker.kt](../../../src/main/kotlin/com/adobe/clawdea/debug/BreakpointTracker.kt), [McpDebugTools.kt](../../../src/main/kotlin/com/adobe/clawdea/debug/McpDebugTools.kt)).
- **Borrowed breakpoint state is restored on cleanup**. When Claude disables a user breakpoint, `trackBorrowedBreakpoint(id, wasDisabled)` records its prior enabled state. `cleanup()` produces `borrowedToReDisable` so re-enabling on session end matches the original state — a previously-disabled user breakpoint stays disabled, an enabled one is re-enabled ([BreakpointTracker.kt](../../../src/main/kotlin/com/adobe/clawdea/debug/BreakpointTracker.kt)).
- **All stepping tools block until the program suspends** at a new location, returning the suspend info. `debug_resume` returns the next suspend position or "running" if no breakpoint is hit within 10 seconds. The blocking semantics are what make the MCP tools composable as a script ([McpDebugTools.kt](../../../src/main/kotlin/com/adobe/clawdea/debug/McpDebugTools.kt), [SuspendGate.kt](../../../src/main/kotlin/com/adobe/clawdea/debug/SuspendGate.kt)).
- **`SuspendGate` arms a `CompletableFuture` per anticipated suspension**. `arm()` cancels any prior pending future and creates a new one; `awaitSuspend(timeout)` blocks the dispatch thread; `onSuspended(info)` completes it. A second `arm()` without `disarm()` cancels the previous wait — callers must arm immediately before triggering the action that would cause suspension, never speculatively ([SuspendGate.kt](../../../src/main/kotlin/com/adobe/clawdea/debug/SuspendGate.kt)).
- **`onSessionEnded(exitCode)` is a sentinel completion**, not a real suspend: `SuspendInfo(file=null, line=-1, method=null, exitCode=<actual>)`. Tool handlers must check `exitCode != -1` to distinguish session-end from a normal suspend, otherwise they'll try to read frames from a dead session ([SuspendGate.kt](../../../src/main/kotlin/com/adobe/clawdea/debug/SuspendGate.kt)).
- **`debug_stop` is mandatory for cleanup**. It removes all Claude-owned breakpoints and re-enables disabled user breakpoints. Without it, a session that ends without a stop call leaves the user's project polluted with Claude's breakpoints ([McpDebugTools.kt](../../../src/main/kotlin/com/adobe/clawdea/debug/McpDebugTools.kt)).

## Resolution pipeline

1. **Launch** — `debug_launch` (named config) or `debug_launch_adhoc` (Java app, JUnit test, JS, Node) calls `SessionLauncher` which configures the IntelliJ run/debug runner. The launch blocks until the session is `running`.
2. **Set breakpoints** — `debug_set_breakpoint(file, line, condition?, log_expression?)` adds the breakpoint to `XBreakpointManager` and registers the id in `BreakpointTracker.claudeBreakpoints`.
3. **Step / resume** — Caller invokes `debug_step_over` (or step-into / step-out / resume / run-to-cursor):
   1. Tool handler calls `SuspendGate.arm()` on the dispatch thread.
   2. Handler invokes the underlying IntelliJ debugger action on the EDT.
   3. Handler calls `gate.awaitSuspend(timeout)` and blocks.
   4. The IDE's debug listener fires on suspend → `gate.onSuspended(info)`.
   5. Handler returns the `SuspendInfo` (file, line, method) to the CLI.
4. **Inspect** — `debug_get_frames`, `debug_get_variables`, `debug_expand_variable`, `debug_evaluate` all require an active suspended session; they return errors if `SuspendGate.future` is null.
5. **Mutate** — `debug_set_value(var_name, value)` calls the runtime to set the variable. Useful for testing fix hypotheses without recompiling.
6. **Stop** — `debug_stop`:
   1. `BreakpointTracker.cleanup()` returns `(claudeBreakpointsToRemove, userBreakpointsToReEnable, borrowedToReDisable)`.
   2. Handler removes Claude breakpoints from `XBreakpointManager`.
   3. Handler re-enables user breakpoints that were disabled.
   4. Handler re-disables borrowed breakpoints that were originally disabled (preserves user state).
   5. Session is terminated.

## Anti-patterns

- **Calling `debug_remove_breakpoint` on a user breakpoint** — `BreakpointTracker.isClaudeOwned(id)` must be checked first. If it's not Claude-owned, return an error and instruct the caller to use `debug_disable_breakpoint` instead. The contract that user breakpoints are sacred is the only reason users trust the integration.
- **Arming `SuspendGate` after triggering the action** — A fast suspend that fires before the future is created leaves no listener; the dispatch thread waits the full timeout and reports "running" even though the program suspended. Always `arm()` first, then trigger.
- **Not checking `SuspendInfo.exitCode` after `awaitSuspend`** — If the session ended (process crashed, user clicked Stop), `onSessionEnded(exitCode)` completes the gate with a sentinel `SuspendInfo`. Treating it as a real suspend will fail every subsequent inspect call.
- **Skipping `debug_stop` in error paths** — A handler that returns early on an exception leaves Claude breakpoints in the project and disabled user breakpoints disabled. All session-terminating paths must invoke cleanup.
- **Holding a stepping tool open through a long suspend** — IntelliJ's debug actions can take seconds; the MCP HTTP timeout still applies. Return the timeout result rather than waiting forever, and let the model decide whether to retry.

## Source pointers

- [McpDebugTools.kt](../../../src/main/kotlin/com/adobe/clawdea/debug/McpDebugTools.kt) — 21 MCP tools (launch, set/remove/enable/disable breakpoint, step over/into/out, resume, pause, run-to-cursor, get frames/variables, expand variable, evaluate, set value, get session, list breakpoints, attach, stop)
- [DebugBridge.kt](../../../src/main/kotlin/com/adobe/clawdea/debug/DebugBridge.kt) — IntelliJ debugger listener, suspend dispatch, session lifecycle
- [BreakpointTracker.kt](../../../src/main/kotlin/com/adobe/clawdea/debug/BreakpointTracker.kt) — Claude vs user vs borrowed breakpoint accounting
- [SuspendGate.kt](../../../src/main/kotlin/com/adobe/clawdea/debug/SuspendGate.kt) — `CompletableFuture`-based suspend gate
- [SessionLauncher.kt](../../../src/main/kotlin/com/adobe/clawdea/debug/SessionLauncher.kt) — launches named or ad-hoc debug configurations
