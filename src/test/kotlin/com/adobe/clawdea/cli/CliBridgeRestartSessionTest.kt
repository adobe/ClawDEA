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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliBridgeRestartSessionTest {

    @Test
    fun `user-triggered restart resumes the current session by default`() {
        assertEquals(
            "current-session",
            CliBridge.resumeSessionForRestart(
                currentSessionId = "current-session",
                requestedResumeSessionId = null,
            ),
        )
    }

    @Test
    fun `explicit resume session wins over the current session`() {
        assertEquals(
            "selected-session",
            CliBridge.resumeSessionForRestart(
                currentSessionId = "current-session",
                requestedResumeSessionId = "selected-session",
            ),
        )
    }

    @Test
    fun `restart starts fresh when no session is known`() {
        assertNull(
            CliBridge.resumeSessionForRestart(
                currentSessionId = null,
                requestedResumeSessionId = null,
            ),
        )
    }

    @Test
    fun `stale reader from deliberate restart must not emit unexpected exit after new process starts`() {
        assertFalse(
            CliBridge.shouldEmitUnexpectedExit(
                readerGeneration = 1,
                activeGeneration = 2,
                expectedExitGeneration = 1,
            ),
        )
    }

    @Test
    fun `current reader emits unexpected exit only when exit was not expected`() {
        assertTrue(
            CliBridge.shouldEmitUnexpectedExit(
                readerGeneration = 2,
                activeGeneration = 2,
                expectedExitGeneration = null,
            ),
        )
        assertFalse(
            CliBridge.shouldEmitUnexpectedExit(
                readerGeneration = 2,
                activeGeneration = 2,
                expectedExitGeneration = 2,
            ),
        )
    }

    @Test
    fun `restarts fresh when CLI rejects the resume session`() {
        assertTrue(
            CliBridge.shouldRecoverFromRejectedResume(
                requestedResumeSessionId = "missing-session",
                recentStderr = listOf("No conversation found with session ID: missing-session"),
            ),
        )
    }

    @Test
    fun `does not recover from non-resume exits`() {
        assertFalse(
            CliBridge.shouldRecoverFromRejectedResume(
                requestedResumeSessionId = null,
                recentStderr = listOf("No conversation found with session ID: missing-session"),
            ),
        )
        assertFalse(
            CliBridge.shouldRecoverFromRejectedResume(
                requestedResumeSessionId = "session",
                recentStderr = listOf("Some other CLI failure"),
            ),
        )
    }

    @Test
    fun `start tracks only nonblank requested resume sessions`() {
        assertEquals(
            "original-session",
            CliBridge.resumableSessionForStart("original-session"),
        )
        assertNull(CliBridge.resumableSessionForStart(null))
        assertNull(CliBridge.resumableSessionForStart(""))
        assertNull(
            CliBridge.resumableSessionForStart("   "),
        )
    }

    @Test
    fun `result session becomes the resumable session`() {
        assertEquals(
            "persisted-session",
            CliBridge.sessionAfterResult(
                currentSessionId = null,
                resultSessionId = "persisted-session",
            ),
        )
    }
}
