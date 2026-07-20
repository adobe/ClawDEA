package com.adobe.clawdea.chat

import com.adobe.clawdea.provider.BackendKind

internal enum class BackendRebuildAction {
    NONE,
    REBUILD_NOW,
    DEFER,
    ALREADY_PENDING,
}

/**
 * Coalesces provider changes while a turn is streaming. The owning bridge's [BackendKind] is fixed,
 * so only the latest selected kind relative to that bridge matters.
 */
internal class BackendRebuildCoordinator {
    private var rebuildPending = false

    fun onBackendSelection(
        currentKind: BackendKind,
        selectedKind: BackendKind,
        isStreaming: Boolean,
    ): BackendRebuildAction {
        if (currentKind == selectedKind) {
            rebuildPending = false
            return BackendRebuildAction.NONE
        }
        if (rebuildPending) {
            return BackendRebuildAction.ALREADY_PENDING
        }
        rebuildPending = true
        if (isStreaming) {
            return BackendRebuildAction.DEFER
        }
        return BackendRebuildAction.REBUILD_NOW
    }

    fun rebuildIfPending(rebuild: () -> Unit): Boolean {
        if (!rebuildPending) return false
        rebuildPending = false
        rebuild()
        return true
    }
}
