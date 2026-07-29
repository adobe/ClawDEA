/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.cli

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the abort-mute scope.
 *
 * On the Claude CLI, abort is SIGINT: the process dies, the next send restarts it, and
 * activeGeneration advances — which is what clears the generation-scoped mute. Codex
 * (`turn/interrupt`) and the OpenAI-compatible HTTP backend (cancel the active job) both survive
 * an abort, so a generation-scoped mute would never clear and every later event of the session
 * would be dropped: one ESC and the tab goes permanently silent. Their mute must be turn-scoped.
 */
class CliBridgeAbortMuteTest {

    @Test
    fun `an unmuted current reader emits`() {
        assertTrue(
            CliBridge.canEmit(
                readerGeneration = 2,
                activeGeneration = 2,
                expectedExitGeneration = null,
                turnMuted = false,
            ),
        )
    }

    @Test
    fun `a turn-muted reader drops the aborted turn's trailing events`() {
        assertFalse(
            CliBridge.canEmit(
                readerGeneration = 2,
                activeGeneration = 2,
                expectedExitGeneration = null,
                turnMuted = true,
            ),
        )
    }

    @Test
    fun `clearing the turn mute restores emission without a generation bump`() {
        // This is the regression: the persistent backends never bump the generation after an
        // abort, so recovery has to come from clearing the turn mute alone.
        assertFalse(
            CliBridge.canEmit(
                readerGeneration = 7,
                activeGeneration = 7,
                expectedExitGeneration = null,
                turnMuted = true,
            ),
        )
        assertTrue(
            CliBridge.canEmit(
                readerGeneration = 7,
                activeGeneration = 7,
                expectedExitGeneration = null,
                turnMuted = false,
            ),
        )
    }

    @Test
    fun `a stale reader still never emits`() {
        assertFalse(
            CliBridge.canEmit(
                readerGeneration = 1,
                activeGeneration = 2,
                expectedExitGeneration = 1,
                turnMuted = false,
            ),
        )
    }

    @Test
    fun `a deliberate exit still suppresses the synthetic unexpected-exit result`() {
        assertFalse(
            CliBridge.canEmit(
                readerGeneration = 3,
                activeGeneration = 3,
                expectedExitGeneration = 3,
                turnMuted = false,
            ),
        )
    }
}
