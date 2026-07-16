package com.adobe.clawdea.chat

import com.adobe.clawdea.provider.BackendKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendRebuildCoordinatorTest {
    @Test
    fun `backend change during streaming is rebuilt when turn becomes idle`() {
        val coordinator = BackendRebuildCoordinator()

        assertEquals(
            BackendRebuildAction.DEFER,
            coordinator.onBackendSelection(
                BackendKind.CLAUDE_CLI,
                BackendKind.OPENAI_COMPATIBLE_HTTP,
                isStreaming = true,
            ),
        )

        var rebuilt = false
        assertTrue(coordinator.rebuildIfPending { rebuilt = true })
        assertTrue(rebuilt)
        assertFalse(coordinator.rebuildIfPending { error("must rebuild only once") })
    }

    @Test
    fun `repeated streaming settings events coalesce into one rebuild`() {
        val coordinator = BackendRebuildCoordinator()

        repeat(3) {
            assertEquals(
                if (it == 0) BackendRebuildAction.DEFER else BackendRebuildAction.ALREADY_PENDING,
                coordinator.onBackendSelection(
                    BackendKind.CLAUDE_CLI,
                    BackendKind.OPENAI_COMPATIBLE_HTTP,
                    isStreaming = true,
                ),
            )
        }

        var rebuildCount = 0
        assertTrue(coordinator.rebuildIfPending { rebuildCount++ })
        assertFalse(coordinator.rebuildIfPending { rebuildCount++ })
        assertEquals(1, rebuildCount)
    }

    @Test
    fun `returning to current backend while streaming cancels deferred rebuild`() {
        val coordinator = BackendRebuildCoordinator()
        coordinator.onBackendSelection(
            BackendKind.CLAUDE_CLI,
            BackendKind.OPENAI_COMPATIBLE_HTTP,
            isStreaming = true,
        )

        assertEquals(
            BackendRebuildAction.NONE,
            coordinator.onBackendSelection(
                BackendKind.CLAUDE_CLI,
                BackendKind.CLAUDE_CLI,
                isStreaming = true,
            ),
        )
        assertFalse(coordinator.rebuildIfPending { error("cancelled rebuild must not run") })
    }

    @Test
    fun `repeated idle backend changes schedule only one rebuild`() {
        val coordinator = BackendRebuildCoordinator()

        assertEquals(
            BackendRebuildAction.REBUILD_NOW,
            coordinator.onBackendSelection(
                BackendKind.CLAUDE_CLI,
                BackendKind.OPENAI_COMPATIBLE_HTTP,
                isStreaming = false,
            ),
        )
        assertEquals(
            BackendRebuildAction.ALREADY_PENDING,
            coordinator.onBackendSelection(
                BackendKind.CLAUDE_CLI,
                BackendKind.OPENAI_COMPATIBLE_HTTP,
                isStreaming = false,
            ),
        )
        var rebuildCount = 0
        assertTrue(coordinator.rebuildIfPending { rebuildCount++ })
        assertFalse(coordinator.rebuildIfPending { rebuildCount++ })
        assertEquals(1, rebuildCount)
    }
}
