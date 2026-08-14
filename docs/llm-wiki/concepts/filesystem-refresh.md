# Filesystem refresh

**Purpose** Keep IntelliJ's VFS, open documents, and VCS state synchronized after agent-driven filesystem changes without making tool or UI callers coordinate platform refresh details.

## Invariants

- `FilesystemRefreshCoordinator` is a **project-level service**. Callers obtain it from the project service container; they must not create ad-hoc refresh implementations.
- Bash completion requests a **debounced broad refresh**. Repeated Bash events coalesce before the VFS and VCS refresh run.
- A known edited file receives a **targeted refresh immediately** after the edit is applied, including document reload and VCS dirtiness updates where applicable.
- Scheduling and platform VFS operations are separate behind `DebounceScheduler` and `RefreshOperations`. This keeps the coordinator unit-testable in headless tests without IntelliJ platform services.
- Callers must use the coordinator rather than invoke VFS refresh APIs directly. The coordinator owns write-safe document reloads, debouncing, and VCS/Git updates.
- Edit review depends on VFS to refresh accepted, rejected, and modified content; VFS never depends on edit review or chat.

## Resolution pipeline

1. A caller reports a known file edit through `onEditApplied(path)`, or a completed Bash command through `onBashCompleted()`.
2. The coordinator delegates targeted refreshes directly to `RefreshOperations`; Bash completion cancels pending work and schedules one broad refresh after the debounce delay.
3. `PlatformRefreshOperations` performs the IntelliJ VFS refresh, updates cached documents in a write-safe context, and refreshes relevant VCS/Git state.
4. Large, single user-visible operations may use `onMassFileChange()` for an immediate broad refresh after the caller has already coalesced changes.

## Source pointers

- [FilesystemRefreshCoordinator.kt](../../../src/main/kotlin/com/adobe/clawdea/vfs/FilesystemRefreshCoordinator.kt) — project service, scheduling boundary, and IntelliJ implementation
- [FilesystemRefreshCoordinatorTest.kt](../../../src/test/kotlin/com/adobe/clawdea/vfs/FilesystemRefreshCoordinatorTest.kt) — headless tests using fake scheduling and refresh operations
- [EditDiffReviewer.kt](../../../src/main/kotlin/com/adobe/clawdea/editreview/EditDiffReviewer.kt) — edit-review consumer of targeted refreshes
